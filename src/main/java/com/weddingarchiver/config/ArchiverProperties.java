package com.weddingarchiver.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "archiver")
public record ArchiverProperties(
		Storage storage,
		Http http,
		Scrape scrape) {

	public record Storage(Path rootPath) {
	}

	public record Http(
			int connectTimeoutMillis,
			int readTimeoutMillis,
			String userAgent,
			long maxAssetBytes,
			long maxTotalBytes) {
	}

	public record Scrape(
			List<String> allowedSchemes,
			boolean followRedirects,
			int maxRedirects,
			String stylesheetSelector,
			Map<String, String> assetSelectors,
			Map<String, String> srcsetSelectors) {
	}
}
