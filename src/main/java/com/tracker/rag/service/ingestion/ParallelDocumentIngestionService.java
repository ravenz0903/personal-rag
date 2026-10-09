package com.tracker.rag.service.ingestion;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.dto.IngestResult;

import java.io.InputStream;
import java.util.concurrent.CompletableFuture;

/**
 * High-throughput parallel document ingestion contract leveraging Java 21 Virtual Threads.
 */
public interface ParallelDocumentIngestionService {

    /**
     * Synchronous staged parallel ingestion for multi-page PDF documents.
     * Extracts pages sequentially and generates embeddings in parallel across virtual threads.
     *
     * @param pdfStream input stream containing PDF bytes
     * @param fileName document identifier / file path
     * @param domain domain scope (must not be null)
     * @return ingestion result with chunk count and hash
     */
    IngestResult ingestPdfParallel(InputStream pdfStream, String fileName, DomainScope domain);

    /**
     * Synchronous staged parallel ingestion for hierarchical Markdown documents.
     * Parses heading hierarchy sequentially and generates chunk embeddings in parallel across virtual threads.
     *
     * @param content markdown text content
     * @param fileName document identifier / file path
     * @param domain domain scope (must not be null)
     * @return ingestion result with chunk count and hash
     */
    IngestResult ingestMarkdownParallel(String content, String fileName, DomainScope domain);

    /**
     * Asynchronous non-blocking PDF ingestion returning a CompletableFuture.
     *
     * @param pdfStream input stream containing PDF bytes
     * @param fileName document identifier / file path
     * @param domain domain scope (must not be null)
     * @return CompletableFuture completing with IngestResult
     */
    CompletableFuture<IngestResult> ingestPdfAsync(InputStream pdfStream, String fileName, DomainScope domain);
}
