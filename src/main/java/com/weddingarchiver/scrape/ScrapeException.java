package com.weddingarchiver.scrape;

public class ScrapeException extends RuntimeException {

	public ScrapeException(String message) {
		super(message);
	}

	public ScrapeException(String message, Throwable cause) {
		super(message, cause);
	}
}
