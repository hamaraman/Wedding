package com.weddingarchiver.domain.archive;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ArchiveRepository extends JpaRepository<Archive, Long> {

	Optional<Archive> findByArchiveUid(String archiveUid);
}
