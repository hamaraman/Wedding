package com.weddingarchiver.domain.archive;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(
		name = "archives",
		indexes = {
				@Index(name = "uk_archives_uid", columnList = "archive_uid", unique = true),
				@Index(name = "idx_archives_created_at", columnList = "created_at")
		}
)
public class Archive {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "archive_uid", nullable = false, unique = true, length = 36)
	private String archiveUid;

	@Column(name = "source_url", nullable = false, length = 2048)
	private String sourceUrl;

	@Column(name = "storage_path", nullable = false, length = 1024)
	private String storagePath;

	@Column(name = "asset_count", nullable = false)
	private int assetCount;

	@Column(name = "total_bytes", nullable = false)
	private long totalBytes;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private ArchiveStatus status;

	@Column(name = "error_message", length = 1024)
	private String errorMessage;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	protected Archive() {
	}

	public Archive(String archiveUid, String sourceUrl, String storagePath) {
		this.archiveUid = archiveUid;
		this.sourceUrl = sourceUrl;
		this.storagePath = storagePath;
		this.status = ArchiveStatus.IN_PROGRESS;
		this.createdAt = Instant.now();
	}

	public void markCompleted(int assetCount, long totalBytes) {
		this.assetCount = assetCount;
		this.totalBytes = totalBytes;
		this.status = ArchiveStatus.COMPLETED;
		this.completedAt = Instant.now();
	}

	public void markFailed(String errorMessage) {
		this.status = ArchiveStatus.FAILED;
		this.errorMessage = errorMessage == null ? null
				: errorMessage.substring(0, Math.min(1024, errorMessage.length()));
		this.completedAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public String getArchiveUid() {
		return archiveUid;
	}

	public String getSourceUrl() {
		return sourceUrl;
	}

	public String getStoragePath() {
		return storagePath;
	}

	public int getAssetCount() {
		return assetCount;
	}

	public long getTotalBytes() {
		return totalBytes;
	}

	public ArchiveStatus getStatus() {
		return status;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}
}
