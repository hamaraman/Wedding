package com.weddingarchiver.scrape;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.weddingarchiver.config.ArchiverProperties;
import com.weddingarchiver.domain.archive.Archive;
import com.weddingarchiver.domain.archive.ArchiveRepository;

/**
 * Orchestrates one archive run:
 *   1. validate the source URL,
 *   2. persist a placeholder Archive row,
 *   3. delegate the actual scraping to ArchiveJob,
 *   4. update the row with the outcome.
 *
 * The expensive scraping step runs outside any DB transaction so we don't hold
 * a row lock open for the duration of a 30-second network pull.
 */
@Service
public class WeddingArchiverService {

	private static final Logger log = LoggerFactory.getLogger(WeddingArchiverService.class);

	private final ArchiverProperties props;
	private final AssetDownloader downloader;
	private final CssRewriter cssRewriter;
	private final ArchiveRepository archiveRepository;

	public WeddingArchiverService(ArchiverProperties props,
			AssetDownloader downloader,
			CssRewriter cssRewriter,
			ArchiveRepository archiveRepository) {
		this.props = props;
		this.downloader = downloader;
		this.cssRewriter = cssRewriter;
		this.archiveRepository = archiveRepository;
	}

	public Archive archive(String sourceUrl) {
		URI source = validateUrl(sourceUrl);

		String uid = UUID.randomUUID().toString();
		Path archiveDir = props.storage().rootPath().toAbsolutePath().resolve(uid);

		Archive archive = new Archive(uid, source.toString(), archiveDir.toString());
		archive = archiveRepository.save(archive);

		try {
			ensureStorageRoot();
			Files.createDirectories(archiveDir);

			ArchiveJob job = new ArchiveJob(source, archiveDir, props, downloader, cssRewriter);
			ArchiveJob.ArchiveResult result = job.run();

			archive.markCompleted(result.assetCount(), result.totalBytes());
			Archive saved = archiveRepository.save(archive);
			log.info("Archived {} → {} ({} assets, {} bytes)",
					source, archiveDir, result.assetCount(), result.totalBytes());
			return saved;

		} catch (ScrapeException e) {
			log.warn("Archive {} failed: {}", uid, e.getMessage());
			archive.markFailed(e.getMessage());
			archiveRepository.save(archive);
			throw e;
		} catch (IOException e) {
			log.warn("Archive {} I/O failure: {}", uid, e.toString());
			archive.markFailed(e.toString());
			archiveRepository.save(archive);
			throw new ScrapeException("I/O failure while archiving " + sourceUrl, e);
		} catch (RuntimeException e) {
			log.error("Archive {} unexpected failure", uid, e);
			archive.markFailed(e.toString());
			archiveRepository.save(archive);
			throw e;
		}
	}

	private URI validateUrl(String sourceUrl) {
		if (sourceUrl == null || sourceUrl.isBlank()) {
			throw new ScrapeException("sourceUrl is required");
		}
		URI uri;
		try {
			uri = new URI(sourceUrl.trim());
		} catch (URISyntaxException e) {
			throw new ScrapeException("Invalid URL: " + sourceUrl, e);
		}
		if (!uri.isAbsolute() || uri.getScheme() == null) {
			throw new ScrapeException("URL must be absolute with a scheme: " + sourceUrl);
		}
		if (!props.scrape().allowedSchemes().contains(uri.getScheme().toLowerCase())) {
			throw new ScrapeException("URL scheme not allowed: " + uri.getScheme());
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			throw new ScrapeException("URL has no host: " + sourceUrl);
		}
		return uri;
	}

	private void ensureStorageRoot() throws IOException {
		Path root = props.storage().rootPath().toAbsolutePath();
		Files.createDirectories(root);
	}
}
