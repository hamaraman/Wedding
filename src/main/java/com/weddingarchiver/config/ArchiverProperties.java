package com.weddingarchiver.config;

import java.nio.file.Path;
import java.util.List;

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
			List<SelectorRule> assetSelectors,
			List<SelectorRule> srcsetSelectors) {
	}

	/**
	 * A CSS selector paired with the attribute that carries the asset URL. Modeled
	 * as a list element rather than a map entry because the selectors contain
	 * {@code [...]} brackets, which Spring Boot's relaxed binding would otherwise
	 * mangle if they appeared as property-map keys.
	 */
	public record SelectorRule(String selector, String attribute) {
	}
}
