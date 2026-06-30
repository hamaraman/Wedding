package com.weddingarchiver.web.dto;

import java.time.Instant;

import com.weddingarchiver.domain.archive.Archive;
import com.weddingarchiver.domain.archive.ArchiveStatus;

public record ArchiveResponse(
		String archiveId,
		String sourceUrl,
		String storagePath,
		int assetCount,
		long totalBytes,
		ArchiveStatus status,
		Instant createdAt,
		Instant completedAt) {

	public static ArchiveResponse from(Archive archive) {
		return new ArchiveResponse(
				archive.getArchiveUid(),
				archive.getSourceUrl(),
				archive.getStoragePath(),
				archive.getAssetCount(),
				archive.getTotalBytes(),
				archive.getStatus(),
				archive.getCreatedAt(),
				archive.getCompletedAt());
	}
}
