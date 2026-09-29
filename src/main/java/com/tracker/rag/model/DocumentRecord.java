package com.tracker.rag.model;

import com.tracker.rag.domain.DomainScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "document_records",
    indexes = {
        @Index(name = "idx_doc_records_domain", columnList = "domain"),
        @Index(name = "idx_doc_records_hash", columnList = "content_hash")
    }
)
public class DocumentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "domain", nullable = false, length = 32)
    private DomainScope domain;

    @NotBlank
    @Size(min = 1, max = 255)
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @NotBlank
    @Size(min = 1, max = 1024)
    @Column(name = "file_path", nullable = false, length = 1024)
    private String filePath;

    @NotBlank
    @Size(min = 1, max = 128)
    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    @NotBlank
    @Size(min = 64, max = 64)
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "page_count", nullable = false)
    private int pageCount = 0;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount = 0;

    @Column(name = "ingested_at", nullable = false, updatable = false)
    private Instant ingestedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public DocumentRecord() {
    }

    public DocumentRecord(DomainScope domain, String fileName, String filePath, String contentType, String contentHash, long fileSize) {
        this.domain = domain;
        this.fileName = fileName;
        this.filePath = filePath;
        this.contentType = contentType;
        this.contentHash = contentHash;
        this.fileSize = fileSize;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.ingestedAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public DomainScope getDomain() { return domain; }
    public void setDomain(DomainScope domain) { this.domain = domain; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public int getPageCount() { return pageCount; }
    public void setPageCount(int pageCount) { this.pageCount = pageCount; }

    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }

    public Instant getIngestedAt() { return ingestedAt; }
    public void setIngestedAt(Instant ingestedAt) { this.ingestedAt = ingestedAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
