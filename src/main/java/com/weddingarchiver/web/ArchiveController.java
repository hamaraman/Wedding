package com.weddingarchiver.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.weddingarchiver.domain.archive.Archive;
import com.weddingarchiver.domain.archive.ArchiveRepository;
import com.weddingarchiver.scrape.WeddingArchiverService;
import com.weddingarchiver.web.dto.ArchiveRequest;
import com.weddingarchiver.web.dto.ArchiveResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/archives")
public class ArchiveController {

	private final WeddingArchiverService archiverService;
	private final ArchiveRepository archiveRepository;

	public ArchiveController(WeddingArchiverService archiverService,
			ArchiveRepository archiveRepository) {
		this.archiverService = archiverService;
		this.archiveRepository = archiveRepository;
	}

	@PostMapping
	public ResponseEntity<ArchiveResponse> create(@Valid @RequestBody ArchiveRequest request) {
		Archive archive = archiverService.archive(request.sourceUrl());
		return ResponseEntity.status(HttpStatus.CREATED).body(ArchiveResponse.from(archive));
	}

	@GetMapping("/{archiveId}")
	public ResponseEntity<ArchiveResponse> get(@PathVariable String archiveId) {
		return archiveRepository.findByArchiveUid(archiveId)
				.map(ArchiveResponse::from)
				.map(ResponseEntity::ok)
				.orElse(ResponseEntity.notFound().build());
	}
}
