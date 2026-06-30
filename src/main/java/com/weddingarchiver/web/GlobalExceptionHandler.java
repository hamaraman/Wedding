package com.weddingarchiver.web;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.weddingarchiver.scrape.ScrapeException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
		String details = ex.getBindingResult().getFieldErrors().stream()
				.map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
				.collect(Collectors.joining(", "));
		return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", details);
	}

	@ExceptionHandler(ScrapeException.class)
	public ResponseEntity<Map<String, Object>> handleScrape(ScrapeException ex) {
		return body(HttpStatus.valueOf(422), "SCRAPE_FAILED", ex.getMessage());
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
		// Spring MVC exceptions (404 NoResourceFoundException, 405, 415, ...) implement
		// ErrorResponse and carry their own status. @ExceptionHandler can't target the
		// interface (not a Throwable), so honor it here instead of masking it as a 500.
		if (ex instanceof ErrorResponse er) {
			HttpStatus status = HttpStatus.valueOf(er.getStatusCode().value());
			String detail = er.getBody().getDetail();
			return body(status, status.name(), detail != null ? detail : status.getReasonPhrase());
		}
		log.error("Unhandled exception", ex);
		return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error");
	}

	private ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).body(Map.of(
				"timestamp", Instant.now().toString(),
				"status", status.value(),
				"code", code,
				"message", message == null ? "" : message));
	}
}
