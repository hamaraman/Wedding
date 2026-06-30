package com.weddingarchiver.scrape;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.weddingarchiver.config.ArchiverProperties;
import com.weddingarchiver.scrape.AssetDownloader.DownloadedAsset;

/**
 * Single archive operation. Holds the per-invocation dedup cache, running byte
 * total, and counters. Not thread-safe and not reusable across archives.
 *
 * <p>Layout produced:
 * <pre>
 *   {archiveDir}/index.html        ← rewritten page, references assets/ siblings
 *   {archiveDir}/assets/{hash}.ext ← every downloaded asset, flat
 * </pre>
 *
 * <p>Path rewriting is context-aware. Asset storage returns only the bare
 * filename; the referrer decides the relative prefix:
 * <ul>
 *   <li>HTML at the archive root references assets as {@code assets/{file}}.</li>
 *   <li>A stylesheet that itself lives in {@code assets/} references its own
 *       assets as plain siblings {@code {file}} — both live in the same dir.</li>
 * </ul>
 * Getting this wrong yields {@code assets/assets/...} and breaks every CSS
 * background image and @font-face, so the distinction is deliberate.
 */
public class ArchiveJob {

	private static final Logger log = LoggerFactory.getLogger(ArchiveJob.class);

	private static final Pattern CHARSET_PARAM = Pattern.compile("charset\\s*=\\s*\"?([\\w-]+)\"?");

	private final URI sourceUri;
	private final Path archiveDir;
	private final Path assetsDir;
	private final ArchiverProperties props;
	private final AssetDownloader downloader;
	private final CssRewriter cssRewriter;

	/** URI → bare stored filename (location-independent). */
	private final Map<URI, String> assetFilenameCache = new HashMap<>();
	private long totalBytes;
	private int assetCount;

	public ArchiveJob(URI sourceUri, Path archiveDir, ArchiverProperties props,
			AssetDownloader downloader, CssRewriter cssRewriter) {
		this.sourceUri = sourceUri;
		this.archiveDir = archiveDir;
		this.assetsDir = archiveDir.resolve("assets");
		this.props = props;
		this.downloader = downloader;
		this.cssRewriter = cssRewriter;
	}

	public ArchiveResult run() {
		try {
			Files.createDirectories(assetsDir);
		} catch (IOException e) {
			throw new ScrapeException("Failed to create archive directory: " + archiveDir, e);
		}

		Connection.Response htmlResp;
		Document doc;
		try {
			htmlResp = Jsoup.connect(sourceUri.toString())
					.userAgent(props.http().userAgent())
					.timeout(props.http().readTimeoutMillis())
					.followRedirects(props.scrape().followRedirects())
					.maxBodySize(0)
					.ignoreContentType(false)
					.execute();
			doc = htmlResp.parse();
		} catch (IOException e) {
			throw new ScrapeException("Failed to fetch HTML: " + sourceUri, e);
		}

		URI baseUri;
		try {
			baseUri = htmlResp.url().toURI();
		} catch (URISyntaxException e) {
			throw new ScrapeException("Invalid final URL after redirects: " + htmlResp.url(), e);
		}
		doc.setBaseUri(baseUri.toString());

		// A <base href> in the source would change relative-path resolution in the
		// archived copy; strip it so our local "assets/..." paths work as-is.
		doc.select("base[href]").remove();

		rewriteStylesheets(doc);
		rewriteBinaryAssets(doc);
		rewriteSrcsets(doc);
		rewriteInlineStyles(doc, baseUri);

		doc.outputSettings()
				.charset(StandardCharsets.UTF_8)
				.prettyPrint(false);

		Path indexHtml = archiveDir.resolve("index.html");
		try {
			Files.writeString(indexHtml, doc.outerHtml(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new ScrapeException("Failed to write index.html", e);
		}

		long htmlBytes = Files.exists(indexHtml) ? safeSize(indexHtml) : 0;
		return new ArchiveResult(assetCount, totalBytes + htmlBytes);
	}

	private void rewriteStylesheets(Document doc) {
		String selector = props.scrape().stylesheetSelector();
		if (selector == null || selector.isBlank()) {
			return;
		}
		for (Element el : doc.select(selector)) {
			URI uri = toUri(el.absUrl("href"));
			if (uri == null) {
				continue;
			}
			String filename = storeStylesheet(uri);
			if (filename != null) {
				el.attr("href", "assets/" + filename);
			}
		}
	}

	private void rewriteBinaryAssets(Document doc) {
		for (ArchiverProperties.SelectorRule rule : props.scrape().assetSelectors()) {
			String selector = rule.selector();
			String attr = rule.attribute();
			for (Element el : doc.select(selector)) {
				URI uri = toUri(el.absUrl(attr));
				if (uri == null) {
					continue;
				}
				String filename = storeBinaryAsset(uri);
				if (filename != null) {
					el.attr(attr, "assets/" + filename);
				}
			}
		}
	}

	private void rewriteSrcsets(Document doc) {
		for (ArchiverProperties.SelectorRule rule : props.scrape().srcsetSelectors()) {
			String selector = rule.selector();
			String attr = rule.attribute();
			for (Element el : doc.select(selector)) {
				String raw = el.attr(attr);
				if (raw == null || raw.isBlank()) {
					continue;
				}
				el.attr(attr, rewriteSrcset(raw, URI.create(el.baseUri())));
			}
		}
	}

	private String rewriteSrcset(String srcset, URI baseUri) {
		String[] entries = srcset.split(",");
		StringBuilder out = new StringBuilder();
		for (String rawEntry : entries) {
			String trimmed = rawEntry.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			String[] parts = trimmed.split("\\s+", 2);
			String url = parts[0];
			String descriptor = parts.length > 1 ? " " + parts[1] : "";
			String replacement = url;
			URI uri;
			try {
				uri = baseUri.resolve(url);
			} catch (IllegalArgumentException e) {
				uri = null;
			}
			if (uri != null && isAllowedScheme(uri)) {
				String filename = storeBinaryAsset(uri);
				if (filename != null) {
					replacement = "assets/" + filename;
				}
			}
			if (out.length() > 0) {
				out.append(", ");
			}
			out.append(replacement).append(descriptor);
		}
		return out.toString();
	}

	private void rewriteInlineStyles(Document doc, URI baseUri) {
		// <style> blocks live in the HTML at the archive root → "assets/" prefix.
		for (Element style : doc.select("style")) {
			String original = style.data();
			if (original == null || original.isEmpty()) {
				continue;
			}
			String rewritten = cssRewriter.rewrite(original, baseUri, this::resolveCssRefFromRoot);
			if (!rewritten.equals(original)) {
				style.text("");
				style.appendChild(new DataNode(rewritten));
			}
		}

		// Inline style="..." attributes also sit at the root.
		for (Element el : doc.select("[style]")) {
			String original = el.attr("style");
			if (original == null || original.isEmpty()) {
				continue;
			}
			String rewritten = cssRewriter.rewrite(original, baseUri, this::resolveCssRefFromRoot);
			if (!rewritten.equals(original)) {
				el.attr("style", rewritten);
			}
		}
	}

	/** Downloads (if new) and returns the bare local filename, or null on failure. */
	private String storeBinaryAsset(URI uri) {
		if (!isAllowedScheme(uri)) {
			return null;
		}
		String cached = assetFilenameCache.get(uri);
		if (cached != null) {
			return cached;
		}

		String localName = buildLocalName(uri, "bin");
		Path localFile = assetsDir.resolve(localName);

		try {
			DownloadedAsset asset = downloader.download(uri);
			if (totalBytes + asset.data().length > props.http().maxTotalBytes()) {
				log.warn("Skipping asset {} — would exceed max-total-bytes", uri);
				return null;
			}
			Files.write(localFile, asset.data());
			totalBytes += asset.data().length;
			assetCount++;
			assetFilenameCache.put(uri, localName);
			return localName;
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			log.warn("Failed to download asset {}: {}", uri, e.toString());
			return null;
		}
	}

	/** Downloads a stylesheet, rewrites its url() refs as siblings, returns filename. */
	private String storeStylesheet(URI uri) {
		if (!isAllowedScheme(uri)) {
			return null;
		}
		String cached = assetFilenameCache.get(uri);
		if (cached != null) {
			return cached;
		}

		String localName = buildLocalName(uri, "css");
		Path localFile = assetsDir.resolve(localName);

		// Reserve the cache entry up-front so @import cycles short-circuit instead
		// of recursing forever (the file is written later in this same call).
		assetFilenameCache.put(uri, localName);

		try {
			DownloadedAsset asset = downloader.download(uri);
			Charset cs = detectCssCharset(asset.contentType());
			String css = new String(asset.data(), cs);
			String rewritten = cssRewriter.rewrite(css, uri, this::resolveCssRefAsSibling);
			byte[] outBytes = rewritten.getBytes(StandardCharsets.UTF_8);
			if (totalBytes + outBytes.length > props.http().maxTotalBytes()) {
				log.warn("Skipping stylesheet {} — would exceed max-total-bytes", uri);
				assetFilenameCache.remove(uri);
				return null;
			}
			Files.write(localFile, outBytes);
			totalBytes += outBytes.length;
			assetCount++;
			return localName;
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			log.warn("Failed to download stylesheet {}: {}", uri, e.toString());
			assetFilenameCache.remove(uri);
			return null;
		}
	}

	/** url() inside an external stylesheet (which lives in assets/): sibling path. */
	private String resolveCssRefAsSibling(URI uri) {
		String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase();
		return path.endsWith(".css") ? storeStylesheet(uri) : storeBinaryAsset(uri);
	}

	/** url() inside inline HTML styles (which live at the root): assets/ prefix. */
	private String resolveCssRefFromRoot(URI uri) {
		String filename = resolveCssRefAsSibling(uri);
		return filename == null ? null : "assets/" + filename;
	}

	private boolean isAllowedScheme(URI uri) {
		if (uri.getScheme() == null) {
			return false;
		}
		return props.scrape().allowedSchemes().contains(uri.getScheme().toLowerCase());
	}

	private URI toUri(String absUrl) {
		if (absUrl == null || absUrl.isBlank()) {
			return null;
		}
		String lower = absUrl.toLowerCase();
		if (lower.startsWith("data:")
				|| lower.startsWith("javascript:")
				|| lower.startsWith("mailto:")
				|| lower.startsWith("tel:")
				|| lower.startsWith("#")) {
			return null;
		}
		try {
			URI uri = URI.create(absUrl);
			return isAllowedScheme(uri) ? uri : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private String buildLocalName(URI uri, String fallbackExt) {
		String hash;
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] raw = md.digest(uri.toString().getBytes(StandardCharsets.UTF_8));
			hash = HexFormat.of().formatHex(raw).substring(0, 32);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
		String ext = extractExtension(uri).orElse(fallbackExt);
		return hash + "." + ext;
	}

	private Optional<String> extractExtension(URI uri) {
		String path = uri.getPath();
		if (path == null || path.isBlank()) {
			return Optional.empty();
		}
		int slash = path.lastIndexOf('/');
		String name = slash >= 0 ? path.substring(slash + 1) : path;
		int dot = name.lastIndexOf('.');
		if (dot < 0 || dot >= name.length() - 1) {
			return Optional.empty();
		}
		String ext = name.substring(dot + 1).toLowerCase().replaceAll("[^a-z0-9]", "");
		if (ext.isEmpty() || ext.length() > 8) {
			return Optional.empty();
		}
		return Optional.of(ext);
	}

	private Charset detectCssCharset(String contentType) {
		if (contentType == null) {
			return StandardCharsets.UTF_8;
		}
		Matcher m = CHARSET_PARAM.matcher(contentType);
		if (m.find()) {
			try {
				return Charset.forName(m.group(1));
			} catch (Exception ignored) {
				// fall through to UTF-8
			}
		}
		return StandardCharsets.UTF_8;
	}

	private long safeSize(Path p) {
		try {
			return Files.size(p);
		} catch (IOException e) {
			return 0;
		}
	}

	public record ArchiveResult(int assetCount, long totalBytes) {
	}
}
