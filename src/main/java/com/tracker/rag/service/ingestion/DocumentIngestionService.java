package com.tracker.rag.service.ingestion;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.dto.DocumentParsingException;
import com.tracker.rag.dto.EmptyDocumentException;
import com.tracker.rag.dto.IngestResult;
import com.tracker.rag.model.DocumentRecord;
import com.tracker.rag.repository.DocumentRecordRepository;
import com.tracker.rag.repository.vector.VectorChunk;
import com.tracker.rag.repository.vector.VectorStore;
import com.tracker.rag.service.ai.AiClient;
import com.tracker.rag.service.retrieval.InvertedIndexService;
import jakarta.annotation.PreDestroy;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Production staged parallel document ingestion engine implementing page-aware PDF extraction (PDFBox 3.0.3),
 * hierarchical Markdown heading tree parsing, SHA-256 deduplication, Java 21 Virtual Threads concurrent
 * embedding generation, and transactional vector storage with inverted index synchronization.
 */
public class DocumentIngestionService implements ParallelDocumentIngestionService, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);
    public static final long DEFAULT_INGESTION_TIMEOUT_SECONDS = 60L;

    protected final VectorStore vectorStore;
    protected final DocumentRecordRepository documentRecordRepository;
    protected final AiClient ollamaClientService;
    protected final InvertedIndexService invertedIndexService;
    protected final Map<String, String> documentHashes = new ConcurrentHashMap<>();
    protected final Map<String, List<VectorChunk>> documentChunks = new ConcurrentHashMap<>();
    @Autowired(required = false)
    protected com.tracker.rag.service.storage.DocumentStorageService originalStorage;
    @Autowired(required = false)
    protected org.springframework.transaction.PlatformTransactionManager transactions;

    private final ExecutorService asyncIngestionExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public DocumentIngestionService(
            VectorStore vectorStore,
            DocumentRecordRepository documentRecordRepository,
            AiClient ollamaClientService,
            @Autowired(required = false) InvertedIndexService invertedIndexService
    ) {
        this.vectorStore = vectorStore;
        this.documentRecordRepository = documentRecordRepository;
        this.ollamaClientService = ollamaClientService;
        this.invertedIndexService = invertedIndexService;
    }

    public DocumentIngestionService(
            VectorStore vectorStore,
            DocumentRecordRepository documentRecordRepository,
            AiClient ollamaClientService
    ) {
        this(vectorStore, documentRecordRepository, ollamaClientService, null);
    }

    @Override
    @PreDestroy
    public void close() {
        if (!asyncIngestionExecutor.isShutdown()) {
            asyncIngestionExecutor.shutdown();
        }
    }

    @Override
    @Transactional
    public IngestResult ingestPdfParallel(InputStream pdfStream, String fileName, DomainScope domain) {
        DomainScope.validateScope(domain);
        if (pdfStream == null) {
            throw new EmptyDocumentException("Cannot ingest null PDF stream: " + fileName);
        }
        try {
            byte[] bytes = pdfStream.readAllBytes();
            return ingestPdf(fileName, domain, bytes);
        } catch (IOException e) {
            throw new DocumentParsingException("Failed to read PDF stream: " + fileName, e);
        }
    }

    @Override
    @Transactional
    public IngestResult ingestMarkdownParallel(String content, String fileName, DomainScope domain) {
        return ingestMarkdown(fileName, domain, content);
    }

    @Override
    public CompletableFuture<IngestResult> ingestPdfAsync(InputStream pdfStream, String fileName, DomainScope domain) {
        DomainScope.validateScope(domain);
        if (pdfStream == null) {
            return CompletableFuture.failedFuture(new EmptyDocumentException("Cannot ingest null PDF stream: " + fileName));
        }
        try {
            byte[] bytes = pdfStream.readAllBytes();
            return CompletableFuture.supplyAsync(
                    () -> transactions == null ? ingestPdf(fileName, domain, bytes) :
                            new org.springframework.transaction.support.TransactionTemplate(transactions)
                                    .execute(status -> ingestPdf(fileName, domain, bytes)),
                    asyncIngestionExecutor
            );
        } catch (IOException e) {
            return CompletableFuture.failedFuture(new DocumentParsingException("Failed to read PDF stream: " + fileName, e));
        }
    }

    @Transactional
    public IngestResult ingestPdf(String filePath, DomainScope domain, byte[] pdfBytes) {
        DomainScope.validateScope(domain);
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new EmptyDocumentException("Cannot ingest empty or 0-byte PDF document: " + filePath);
        }

        String fileHash = computeSha256(pdfBytes);
        String docKey = filePath + ":" + domain;

        if (domain == DomainScope.PERSONAL && originalStorage != null) {
            return storePrivateDocument(filePath, domain, pdfBytes, "application/pdf", fileHash);
        }

        if (fileHash.equals(documentHashes.get(docKey)) || (documentRecordRepository != null && documentRecordRepository.findByFilePathAndDomain(filePath, domain).map(doc -> fileHash.equals(doc.getContentHash()) && ollamaClientService.embeddingSpace().equals(doc.getEmbeddingModelId())).orElse(false))) {
            return IngestResult.skipped(filePath, fileHash);
        }

        boolean isUpdate = documentHashes.containsKey(docKey) || 
                (documentRecordRepository != null && documentRecordRepository.findByFilePathAndDomain(filePath, domain).isPresent());

        // Phase 1: Sequential in-memory text extraction into intermediate DTOs
        List<PageRawText> rawPages = extractPdfPages(filePath, pdfBytes);
        rawPages = rawPages.stream().flatMap(page -> splitText(page.text()).stream()
                .map(text -> new PageRawText(page.pageNumber(), text))).toList();
        if (originalStorage != null && rawPages.size() > 16) throw new IllegalArgumentException("For the free AI tier, upload up to 16 text sections at a time. Split this document into smaller files.");
        if (rawPages.isEmpty()) {
            throw new EmptyDocumentException("Zero extractable text found in PDF: " + filePath);
        }

        // Phase 2: Parallel chunk embedding generation across Java 21 Virtual Threads
        List<VectorChunk> newChunks;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<VectorChunk>> futures = rawPages.stream()
                    .map(page -> CompletableFuture.supplyAsync(() -> {
                        float[] emb = ollamaClientService.generateEmbedding(page.text(), domain);
                        return new VectorChunk(
                                UUID.randomUUID(),
                                filePath,
                                domain,
                                page.pageNumber(),
                                null,
                                page.text(),
                                emb
                        );
                    }, executor).orTimeout(DEFAULT_INGESTION_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .toList();

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(DEFAULT_INGESTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                // Collect futures in strict stream order preserving 1-indexed page numbering
                newChunks = futures.stream()
                        .map(CompletableFuture::join)
                        .toList();
            } catch (TimeoutException te) {
                throw new DocumentParsingException("Timed out generating embeddings after "
                        + DEFAULT_INGESTION_TIMEOUT_SECONDS + "s for document: " + filePath, te);
            } catch (ExecutionException | CompletionException ce) {
                Throwable cause = ce.getCause() != null ? ce.getCause() : ce;
                if (cause instanceof TimeoutException te) {
                    throw new DocumentParsingException("Timed out generating embeddings after "
                            + DEFAULT_INGESTION_TIMEOUT_SECONDS + "s for document: " + filePath, te);
                }
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new DocumentParsingException("Failed to generate chunk embeddings: " + cause.getMessage(), cause);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new DocumentParsingException("Embedding generation interrupted for document: " + filePath, ie);
            }
        }

        // Phase 3: Batch persist chunks into VectorStore and InvertedIndexService (deferred deletion ensures atomicity)
        String storagePath = originalStorage == null ? null : originalStorage.save(pdfBytes, domain.name(), extractFilename(filePath), "application/pdf");
        if (isUpdate) {
            vectorStore.deleteByDocumentId(filePath, domain);
        }

        vectorStore.saveAll(newChunks);
        publishChunks(docKey, filePath, domain, fileHash, newChunks, isUpdate);

        if (documentRecordRepository != null) {
            String fileName = extractFilename(filePath);
            DocumentRecord doc = documentRecordRepository.findByFilePathAndDomain(filePath, domain)
                    .orElse(new DocumentRecord(domain, fileName, filePath, "application/pdf", fileHash, pdfBytes.length));
            doc.setContentHash(fileHash);
            doc.setEmbeddingModelId(ollamaClientService.embeddingSpace());
            doc.setStoragePath(storagePath);
            doc.setChunkCount(newChunks.size());
            doc.setFileSize(pdfBytes.length);
            doc.setUpdatedAt(Instant.now());
            documentRecordRepository.save(doc);
        }

        log.info("[Ingestion] Ingested PDF {} ({} chunks, hash={}) via virtual threads", filePath, newChunks.size(), fileHash);
        return isUpdate
                ? IngestResult.updated(filePath, newChunks.size(), fileHash)
                : IngestResult.created(filePath, newChunks.size(), fileHash);
    }

    protected List<PageRawText> extractPdfPages(String filePath, byte[] pdfBytes) {
        List<PageRawText> rawPages = new ArrayList<>();

        if (isPdfPayload(pdfBytes)) {
            try (PDDocument document = Loader.loadPDF(pdfBytes)) {
                int totalPages = document.getNumberOfPages();
                if (totalPages == 0) {
                    throw new EmptyDocumentException("Zero extractable pages found in PDF: " + filePath);
                }
                PDFTextStripper stripper = new PDFTextStripper();
                for (int p = 1; p <= totalPages; p++) {
                    stripper.setStartPage(p);
                    stripper.setEndPage(p);
                    String pageText = stripper.getText(document).trim();
                    if (!pageText.isEmpty()) {
                        rawPages.add(new PageRawText(p, pageText));
                    }
                }
                return rawPages;
            } catch (EmptyDocumentException ede) {
                throw ede;
            } catch (Exception e) {
                throw new DocumentParsingException("Failed to parse PDF document: " + filePath, e);
            }
        }

        // Fallback: ONLY executed for synthetic test fixtures and text documents
        String rawContent = new String(pdfBytes, StandardCharsets.UTF_8);
        if (rawContent.contains("---PAGE_BREAK---")) {
            String[] pages = rawContent.split("(?i)---PAGE_BREAK---");
            for (int p = 0; p < pages.length; p++) {
                String pageText = pages[p].trim();
                if (!pageText.isEmpty()) {
                    rawPages.add(new PageRawText(p + 1, pageText));
                }
            }
        } else {
            String plain = rawContent.trim();
            if (!plain.isEmpty()) {
                rawPages.add(new PageRawText(1, plain));
            }
        }
        return rawPages;
    }

    private static boolean isPdfPayload(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return false;
        int limit = Math.min(bytes.length - 3, 1024);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == '%' && bytes[i + 1] == 'P' && bytes[i + 2] == 'D' && bytes[i + 3] == 'F') {
                return true;
            }
        }
        return false;
    }

    @Transactional
    public IngestResult ingestMarkdown(String filePath, DomainScope domain, String markdownContent) {
        DomainScope.validateScope(domain);
        if (markdownContent == null || markdownContent.trim().isEmpty()) {
            throw new EmptyDocumentException("Cannot ingest empty Markdown document: " + filePath);
        }

        byte[] bytes = markdownContent.getBytes(StandardCharsets.UTF_8);
        String fileHash = computeSha256(bytes);
        String docKey = filePath + ":" + domain;

        if (domain == DomainScope.PERSONAL && originalStorage != null) {
            return storePrivateDocument(filePath, domain, bytes, "text/markdown", fileHash);
        }

        if (fileHash.equals(documentHashes.get(docKey)) || (documentRecordRepository != null && documentRecordRepository.findByFilePathAndDomain(filePath, domain).map(doc -> fileHash.equals(doc.getContentHash()) && ollamaClientService.embeddingSpace().equals(doc.getEmbeddingModelId())).orElse(false))) {
            return IngestResult.skipped(filePath, fileHash);
        }

        boolean isUpdate = documentHashes.containsKey(docKey) ||
                (documentRecordRepository != null && documentRecordRepository.findByFilePathAndDomain(filePath, domain).isPresent());

        // Phase 1: Sequential heading hierarchy parsing into intermediate section records
        List<MarkdownSectionRaw> rawSections = parseMarkdownSections(filePath, markdownContent);
        rawSections = rawSections.stream().flatMap(section -> splitText(section.text()).stream()
                .map(text -> new MarkdownSectionRaw(section.index(), section.headingPath(), text))).toList();
        if (originalStorage != null && rawSections.size() > 16) throw new IllegalArgumentException("For the free AI tier, upload up to 16 text sections at a time. Split this document into smaller files.");
        if (rawSections.isEmpty()) {
            throw new EmptyDocumentException("No content chunks parsed from Markdown: " + filePath);
        }

        // Phase 2: Parallel chunk embedding generation across Java 21 Virtual Threads
        List<VectorChunk> newChunks;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<VectorChunk>> futures = rawSections.stream()
                    .map(sec -> CompletableFuture.supplyAsync(() -> {
                        float[] emb = ollamaClientService.generateEmbedding(sec.text(), domain);
                        return new VectorChunk(
                                UUID.randomUUID(),
                                filePath,
                                domain,
                                0,
                                sec.headingPath(),
                                sec.text(),
                                emb
                        );
                    }, executor).orTimeout(DEFAULT_INGESTION_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .toList();

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(DEFAULT_INGESTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                // Collect futures in strict stream order preserving document sequence
                newChunks = futures.stream()
                        .map(CompletableFuture::join)
                        .toList();
            } catch (TimeoutException te) {
                throw new DocumentParsingException("Timed out generating embeddings after "
                        + DEFAULT_INGESTION_TIMEOUT_SECONDS + "s for Markdown document: " + filePath, te);
            } catch (ExecutionException | CompletionException ce) {
                Throwable cause = ce.getCause() != null ? ce.getCause() : ce;
                if (cause instanceof TimeoutException te) {
                    throw new DocumentParsingException("Timed out generating embeddings after "
                            + DEFAULT_INGESTION_TIMEOUT_SECONDS + "s for Markdown document: " + filePath, te);
                }
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new DocumentParsingException("Failed to generate chunk embeddings: " + cause.getMessage(), cause);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new DocumentParsingException("Embedding generation interrupted for document: " + filePath, ie);
            }
        }

        // Phase 3: Batch persist chunks into VectorStore and InvertedIndexService (deferred deletion ensures atomicity)
        String storagePath = originalStorage == null ? null : originalStorage.save(bytes, domain.name(), extractFilename(filePath), "text/markdown");
        if (isUpdate) {
            vectorStore.deleteByDocumentId(filePath, domain);
        }

        vectorStore.saveAll(newChunks);
        publishChunks(docKey, filePath, domain, fileHash, newChunks, isUpdate);

        if (documentRecordRepository != null) {
            String fileName = extractFilename(filePath);
            DocumentRecord doc = documentRecordRepository.findByFilePathAndDomain(filePath, domain)
                    .orElse(new DocumentRecord(domain, fileName, filePath, "text/markdown", fileHash, bytes.length));
            doc.setContentHash(fileHash);
            doc.setEmbeddingModelId(ollamaClientService.embeddingSpace());
            doc.setStoragePath(storagePath);
            doc.setChunkCount(newChunks.size());
            doc.setFileSize(bytes.length);
            doc.setUpdatedAt(Instant.now());
            documentRecordRepository.save(doc);
        }

        log.info("[Ingestion] Ingested Markdown {} ({} chunks, hash={}) via virtual threads", filePath, newChunks.size(), fileHash);
        return isUpdate
                ? IngestResult.updated(filePath, newChunks.size(), fileHash)
                : IngestResult.created(filePath, newChunks.size(), fileHash);
    }

    protected List<MarkdownSectionRaw> parseMarkdownSections(String filePath, String markdownContent) {
        List<MarkdownSectionRaw> sections = new ArrayList<>();
        Deque<HeadingEntry> stack = new ArrayDeque<>();
        StringBuilder sectionBuffer = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new StringReader(markdownContent))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int level = countHeadingLevel(line);
                if (level > 0) {
                    flushMarkdownRawSection(sections, stack, sectionBuffer);
                    String headingTitle = line.substring(level).trim();
                    while (!stack.isEmpty() && stack.peek().level() >= level) {
                        stack.pop();
                    }
                    stack.push(new HeadingEntry(level, headingTitle));
                } else {
                    sectionBuffer.append(line).append("\n");
                }
            }
            flushMarkdownRawSection(sections, stack, sectionBuffer);
        } catch (Exception e) {
            throw new DocumentParsingException("Failed to parse Markdown document: " + filePath, e);
        }
        return sections;
    }

    private void flushMarkdownRawSection(List<MarkdownSectionRaw> sections, Deque<HeadingEntry> stack, StringBuilder buffer) {
        String text = buffer.toString().trim();
        buffer.setLength(0);
        if (text.isEmpty()) return;

        String headingPath = buildHeadingPath(stack);
        sections.add(new MarkdownSectionRaw(sections.size(), headingPath, text));
    }

    protected String buildHeadingPath(Deque<HeadingEntry> stack) {
        if (stack.isEmpty()) return null;
        List<HeadingEntry> list = new ArrayList<>(stack);
        Collections.reverse(list);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(" > ");
            sb.append(list.get(i).title());
        }
        return sb.toString();
    }

    protected int countHeadingLevel(String line) {
        if (!line.startsWith("#")) return 0;
        int count = 0;
        while (count < line.length() && line.charAt(count) == '#') {
            count++;
        }
        return (count < line.length() && Character.isWhitespace(line.charAt(count))) ? count : 0;
    }

    protected String computeSha256(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 calculation failed", e);
        }
    }

    protected String extractFilename(String path) {
        if (path == null) return "untitled";
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return (lastSlash >= 0) ? path.substring(lastSlash + 1) : path;
    }

    public List<VectorChunk> getStoredChunks(String filePath, DomainScope domain) {
        return documentChunks.getOrDefault(filePath + ":" + domain, Collections.emptyList());
    }

    private List<String> splitText(String text) {
        List<String> parts = new ArrayList<>();
        for (int start = 0; start < text.length(); start += 5600) {
            parts.add(text.substring(start, Math.min(text.length(), start + 6000)));
            if (start + 6000 >= text.length()) break;
        }
        return parts;
    }

    private void publishChunks(String key, String path, DomainScope domain, String hash, List<VectorChunk> chunks, boolean update) {
        Runnable publish = () -> {
            documentHashes.put(key, hash);
            documentChunks.put(key, chunks);
            if (invertedIndexService != null) {
                if (update) invertedIndexService.removeDocument(path, domain);
                invertedIndexService.indexChunks(chunks);
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { publish.run(); }
                });
        } else publish.run();
    }

    private IngestResult storePrivateDocument(String path, DomainScope domain, byte[] bytes, String type, String hash) {
        Optional<DocumentRecord> existing = documentRecordRepository.findByFilePathAndDomain(path, domain);
        if (existing.isPresent() && hash.equals(existing.get().getContentHash())) return IngestResult.skipped(path, hash);
        String storagePath = originalStorage.save(bytes, domain.name(), extractFilename(path), type);
        DocumentRecord record = existing.orElseGet(() -> new DocumentRecord(domain, extractFilename(path), path, type, hash, bytes.length));
        record.setContentHash(hash); record.setStoragePath(storagePath); record.setChunkCount(0); record.setUpdatedAt(Instant.now());
        documentRecordRepository.save(record);
        return existing.isPresent() ? IngestResult.updated(path, 0, hash) : IngestResult.created(path, 0, hash);
    }

    public record HeadingEntry(int level, String title) {}
    public record PageRawText(int pageNumber, String text) {}
    public record MarkdownSectionRaw(int index, String headingPath, String text) {}
}
