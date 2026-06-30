package com.weddingarchiver.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

public record ArchiveRequest(
		@NotBlank
		@Size(max = 2048)
		@URL(protocol = "https")
		String sourceUrl) {
}
