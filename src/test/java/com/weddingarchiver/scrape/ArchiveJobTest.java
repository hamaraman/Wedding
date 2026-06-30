package com.weddingarchiver.scrape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;
import com.weddingarchiver.config.ArchiverProperties;

/**
 * Exercises the scraping engine end-to-end against a local fixture server so the
 * test is deterministic and offline. Verifies asset download, dedup, and — most
 * importantly — context-aware path rewriting (root-relative for HTML,
 * sibling-relative for CSS that lives in assets/).
 */
class ArchiveJobTest {

	private static final byte[] PNG = Base64.getDecoder().decode(
			"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

	private static final String INDEX_HTML = """
			<!doctype html>
			<html>
			<head>
			<meta charset="utf-8">
			<title>청첩장</title>
			<link rel="stylesheet" href="css/style.css">
			<link rel="icon" href="img/photo.png">
			<style>.hero{background:url('img/bg.png')}</style>
			</head>
			<body>
			<h1>우리 결혼합니다 💍</h1>
			<img src="img/photo.png" srcset="img/photo.png 1x, img/bg.png 2x" alt="photo">
			<script src="js/app.js"></script>
			</body>
			</html>
			""";

	private static final String STYLE_CSS = """
			/* 한글 주석 - UTF-8 검증 */
			body { background: url(../img/bg.png); }
			@font-face { font-family: x; src: url("../img/photo.png"); }
			""";

	private HttpServer server;
	private String baseUrl;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			byte[] body;
			String contentType;
			if (path.equals("/") || path.equals("/index.html")) {
				body = INDEX_HTML.getBytes(StandardCharsets.UTF_8);
				contentType = "text/html; charset=utf-8";
			} else if (path.equals("/css/style.css")) {
				body = STYLE_CSS.getBytes(StandardCharsets.UTF_8);
				contentType = "text/css; charset=utf-8";
			} else if (path.equals("/js/app.js")) {
				body = "console.log('hi');".getBytes(StandardCharsets.UTF_8);
				contentType = "application/javascript";
			} else if (path.equals("/img/photo.png") || path.equals("/img/bg.png")) {
				body = PNG;
				contentType = "image/png";
			} else {
				body = "not found".getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(404, body.length);
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(body);
				}
				return;
			}
			exchange.getResponseHeaders().add("Content-Type", contentType);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(body);
			}
		});
		server.start();
		baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void archivesPageWithContextAwarePaths(@TempDir Path tempDir) throws IOException {
		ArchiverProperties props = testProps(tempDir);
		Path archiveDir = tempDir.resolve("archive");

		ArchiveJob job = new ArchiveJob(
				URI.create(baseUrl + "/index.html"),
				archiveDir,
				props,
				new AssetDownloader(props),
				new CssRewriter());

		ArchiveJob.ArchiveResult result = job.run();

		// style.css, app.js, photo.png, bg.png — each unique URL stored exactly once.
		assertEquals(4, result.assetCount(), "expected 4 deduplicated assets");

		Path indexHtml = archiveDir.resolve("index.html");
		assertTrue(Files.exists(indexHtml), "index.html must be written");
		String html = Files.readString(indexHtml, StandardCharsets.UTF_8);

		// No reference to the origin server survives.
		assertFalse(html.contains("127.0.0.1"), "all origin URLs must be rewritten:\n" + html);

		// HTML at the root references assets via the assets/ prefix.
		assertTrue(html.contains("href=\"assets/"), "stylesheet/icon links rewritten");
		assertTrue(html.contains("src=\"assets/"), "img/script src rewritten");
		// srcset: both candidates rewritten to assets/ paths, origin gone.
		var srcsetM = Pattern.compile("srcset=\"([^\"]*)\"").matcher(html);
		assertTrue(srcsetM.find(), "srcset attribute present");
		String srcset = srcsetM.group(1);
		assertEquals(2, countOccurrences(srcset, "assets/"), "both srcset candidates rewritten");
		assertFalse(srcset.contains("127.0.0.1"), "srcset fully rewritten");
		// Inline <style> url() is root-context → assets/ prefix.
		assertTrue(html.contains("url(\"assets/"), "inline style url() uses assets/ prefix");

		// Exactly 4 files on disk under assets/.
		Path assetsDir = archiveDir.resolve("assets");
		try (var files = Files.list(assetsDir)) {
			assertEquals(4, files.count(), "assets dir should hold 4 files");
		}

		// The stored stylesheet must reference its assets as SIBLINGS (no assets/ prefix),
		// because the CSS file itself lives inside assets/. This is the path-bug guard.
		Path cssFile;
		try (var files = Files.list(assetsDir)) {
			cssFile = files.filter(p -> p.toString().endsWith(".css")).findFirst().orElseThrow();
		}
		String css = Files.readString(cssFile, StandardCharsets.UTF_8);
		assertFalse(css.contains("assets/"), "CSS must use sibling paths, not assets/:\n" + css);
		assertTrue(Pattern.compile("url\\(\"[0-9a-f]{32}\\.png\"\\)").matcher(css).find(),
				"CSS url() rewritten to a sibling hash filename:\n" + css);
		// UTF-8 round-trip preserved the Korean comment.
		assertTrue(css.contains("한글 주석"), "CSS UTF-8 content preserved");
	}

	private int countOccurrences(String haystack, String needle) {
		int count = 0;
		int idx = 0;
		while ((idx = haystack.indexOf(needle, idx)) != -1) {
			count++;
			idx += needle.length();
		}
		return count;
	}

	private ArchiverProperties testProps(Path tempDir) {
		return new ArchiverProperties(
				new ArchiverProperties.Storage(tempDir),
				new ArchiverProperties.Http(5000, 10000, "TestBot/1.0", 50_000_000L, 500_000_000L),
				new ArchiverProperties.Scrape(
						List.of("http", "https"),
						true,
						5,
						"link[rel=stylesheet][href]",
						List.of(
								new ArchiverProperties.SelectorRule("link[rel=icon][href]", "href"),
								new ArchiverProperties.SelectorRule("script[src]", "src"),
								new ArchiverProperties.SelectorRule("img[src]", "src"),
								new ArchiverProperties.SelectorRule("source[src]", "src")),
						List.of(
								new ArchiverProperties.SelectorRule("img[srcset]", "srcset"),
								new ArchiverProperties.SelectorRule("source[srcset]", "srcset"))));
	}
}
