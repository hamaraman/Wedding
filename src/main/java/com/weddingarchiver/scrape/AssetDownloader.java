package com.weddingarchiver.scrape;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.stereotype.Component;

import com.weddingarchiver.config.ArchiverProperties;

/**
 * Pulls remote bytes with timeout, size, and User-Agent controls. Returns the
 * raw body together with the response Content-Type so callers can decide on
 * file extension and CSS rewriting.
 */
@Component
public class AssetDownloader {

	private final ArchiverProperties props;
	private final HttpClient httpClient;

	public AssetDownloader(ArchiverProperties props) {
		this.props = props;
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofMillis(props.http().connectTimeoutMillis()))
				.followRedirects(props.scrape().followRedirects()
						? HttpClient.Redirect.NORMAL
						: HttpClient.Redirect.NEVER)
				.build();
	}

	public DownloadedAsset download(URI uri) throws IOException, InterruptedException {
		HttpRequest req = HttpRequest.newBuilder(uri)
				.timeout(Duration.ofMillis(props.http().readTimeoutMillis()))
				.header("User-Agent", props.http().userAgent())
				.header("Accept", "*/*")
				.GET()
				.build();

		HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());

		if (resp.statusCode() / 100 != 2) {
			throw new IOException("HTTP " + resp.statusCode() + " for " + uri);
		}

		byte[] data = resp.body();
		if (data.length > props.http().maxAssetBytes()) {
			throw new IOException("Asset exceeds max-asset-bytes (" + data.length + " > "
					+ props.http().maxAssetBytes() + "): " + uri);
		}

		String contentType = resp.headers().firstValue("Content-Type")
				.orElse("application/octet-stream");

		return new DownloadedAsset(data, contentType);
	}

	public record DownloadedAsset(byte[] data, String contentType) {
	}
}
