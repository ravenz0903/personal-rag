package com.tracker.rag.repository;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.model.DocumentRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRecordRepository extends JpaRepository<DocumentRecord, UUID> {

    List<DocumentRecord> findByDomain(DomainScope domain);

    List<DocumentRecord> findByDomainIn(Collection<DomainScope> domains);

    Optional<DocumentRecord> findByContentHash(String contentHash);

    Optional<DocumentRecord> findByFilePath(String filePath);

    Optional<DocumentRecord> findByIdAndDomain(UUID id, DomainScope domain);

    Optional<DocumentRecord> findByIdAndDomainIn(UUID id, Collection<DomainScope> domains);

    boolean existsByContentHash(String contentHash);

    void deleteByFilePath(String filePath);
}
