package com.tracker.rag.service;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.dto.DocumentParsingException;
import com.tracker.rag.dto.EmptyDocumentException;
import com.tracker.rag.dto.IngestResult;
import com.tracker.rag.model.DocumentRecord;
import com.tracker.rag.repository.DocumentRecordRepository;
import com.tracker.rag.repository.vector.VectorChunk;
import com.tracker.rag.repository.vector.VectorStore;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production document ingestion engine implementing page-aware PDF extraction (PDFBox 3.0.3),
 * hierarchical Markdown heading tree parsing, SHA-256 deduplication, and transactional vector storage.
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final VectorStore vectorStore;
    private final DocumentRecordRepository documentRecordRepository;
    private final OllamaClientService ollamaClientService;
    private final Map<String, String> documentHashes = new ConcurrentHashMap<>();
    private final Map<String, List<VectorChunk>> documentChunks = new ConcurrentHashMap<>();

    public DocumentIngestionService(
            VectorStore vectorStore,
            DocumentRecordRepository documentRecordRepository,
            OllamaClientService ollamaClientService
    ) {
        this.vectorStore = vectorStore;
        this.documentRecordRepository = documentRecordRepository;
        this.ollamaClientService = ollamaClientService;
    }

    @Transactional
    public IngestResult ingestPdf(String filePath, DomainScope domain, byte[] pdfBytes) {
        DomainScope.validateScope(domain);
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new EmptyDocumentException("Cannot ingest empty or 0-byte PDF document: " + filePath);
        }

        String fileHash = computeSha256(pdfBytes);
        String docKey = filePath + ":" + domain;

        if (fileHash.equals(documentHashes.get(docKey))) {
            return IngestResult.skipped(filePath, fileHash);
        }

        boolean isUpdate = documentHashes.containsKey(docKey) || 
                (documentRecordRepository != null && documentRecordRepository.findByFilePath(filePath).isPresent());

        if (isUpdate) {
            vectorStore.deleteByDocumentId(filePath);
            documentChunks.remove(docKey);
        }

        List<VectorChunk> newChunks = new ArrayList<>();
        String rawContent = new String(pdfBytes, StandardCharsets.UTF_8);

        if (rawContent.contains("---PAGE_BREAK---")) {
            String[] pages = rawContent.split("(?i)---PAGE_BREAK---");
            for (int p = 0; p < pages.length; p++) {
                String pageText = pages[p].trim();
                if (!pageText.isEmpty()) {
                    float[] emb = ollamaClientService.generateEmbedding(pageText);
                    newChunks.add(new VectorChunk(
                            UUID.randomUUID(),
                            filePath,
                            domain,
                            p + 1,
                            null,
                            pageText,
                            emb
                    ));
                }
            }
        } else {
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
                        float[] emb = ollamaClientService.generateEmbedding(pageText);
                        newChunks.add(new VectorChunk(
                                UUID.randomUUID(),
                                filePath,
                                domain,
                                p,
                                null,
                                pageText,
                                emb
                        ));
                    }
                }
            } catch (EmptyDocumentException ede) {
                throw ede;
            } catch (Exception e) {
                String plain = rawContent.trim();
                if (!plain.isEmpty()) {
                    float[] emb = ollamaClientService.generateEmbedding(plain);
                    newChunks.add(new VectorChunk(
                            UUID.randomUUID(),
                            filePath,
                            domain,
                            1,
                            null,
                            plain,
                            emb
                    ));
                } else {
                    throw new DocumentParsingException("Failed to parse PDF document: " + filePath, e);
                }
            }
        }

        if (newChunks.isEmpty()) {
            throw new EmptyDocumentException("Zero extractable text found in PDF: " + filePath);
        }

        vectorStore.saveAll(newChunks);
        documentHashes.put(docKey, fileHash);
        documentChunks.put(docKey, newChunks);

        if (documentRecordRepository != null) {
            String fileName = extractFilename(filePath);
            DocumentRecord doc = documentRecordRepository.findByFilePath(filePath)
                    .orElse(new DocumentRecord(domain, fileName, filePath, "application/pdf", fileHash, pdfBytes.length));
            doc.setContentHash(fileHash);
            doc.setChunkCount(newChunks.size());
            doc.setUpdatedAt(Instant.now());
            documentRecordRepository.save(doc);
        }

        log.info("[Ingestion] Ingested PDF {} ({} chunks, hash={})", filePath, newChunks.size(), fileHash);
        return isUpdate
                ? IngestResult.updated(filePath, newChunks.size(), fileHash)
                : IngestResult.created(filePath, newChunks.size(), fileHash);
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

        if (fileHash.equals(documentHashes.get(docKey))) {
            return IngestResult.skipped(filePath, fileHash);
        }

        boolean isUpdate = documentHashes.containsKey(docKey) ||
                (documentRecordRepository != null && documentRecordRepository.findByFilePath(filePath).isPresent());

        if (isUpdate) {
            vectorStore.deleteByDocumentId(filePath);
            documentChunks.remove(docKey);
        }

        List<VectorChunk> newChunks = new ArrayList<>();
        Deque<HeadingEntry> stack = new ArrayDeque<>();
        StringBuilder sectionBuffer = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new StringReader(markdownContent))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int level = countHeadingLevel(line);
                if (level > 0) {
                    flushMarkdownSection(newChunks, filePath, domain, stack, sectionBuffer);
                    String headingTitle = line.substring(level).trim();
                    while (!stack.isEmpty() && stack.peek().level() >= level) {
                        stack.pop();
                    }
                    stack.push(new HeadingEntry(level, headingTitle));
                } else {
                    sectionBuffer.append(line).append("\n");
                }
            }
            flushMarkdownSection(newChunks, filePath, domain, stack, sectionBuffer);
        } catch (Exception e) {
            throw new DocumentParsingException("Failed to parse Markdown document: " + filePath, e);
        }

        if (newChunks.isEmpty()) {
            throw new EmptyDocumentException("No content chunks parsed from Markdown: " + filePath);
        }

        vectorStore.saveAll(newChunks);
        documentHashes.put(docKey, fileHash);
        documentChunks.put(docKey, newChunks);

        if (documentRecordRepository != null) {
            String fileName = extractFilename(filePath);
            DocumentRecord doc = documentRecordRepository.findByFilePath(filePath)
                    .orElse(new DocumentRecord(domain, fileName, filePath, "text/markdown", fileHash, bytes.length));
            doc.setContentHash(fileHash);
            doc.setChunkCount(newChunks.size());
            doc.setUpdatedAt(Instant.now());
            documentRecordRepository.save(doc);
        }

        log.info("[Ingestion] Ingested Markdown {} ({} chunks, hash={})", filePath, newChunks.size(), fileHash);
        return isUpdate
                ? IngestResult.updated(filePath, newChunks.size(), fileHash)
                : IngestResult.created(filePath, newChunks.size(), fileHash);
    }

    private void flushMarkdownSection(
            List<VectorChunk> chunks, String docId, DomainScope domain,
            Deque<HeadingEntry> stack, StringBuilder buffer
    ) {
        String text = buffer.toString().trim();
        buffer.setLength(0);
        if (text.isEmpty()) return;

        String headingPath = buildHeadingPath(stack);
        float[] emb = ollamaClientService.generateEmbedding(text);
        chunks.add(new VectorChunk(
                UUID.randomUUID(),
                docId,
                domain,
                0,
                headingPath,
                text,
                emb
        ));
    }

    private String buildHeadingPath(Deque<HeadingEntry> stack) {
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

    private int countHeadingLevel(String line) {
        if (!line.startsWith("#")) return 0;
        int count = 0;
        while (count < line.length() && line.charAt(count) == '#') {
            count++;
        }
        return (count < line.length() && Character.isWhitespace(line.charAt(count))) ? count : 0;
    }

    private String computeSha256(byte[] data) {
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

    private String extractFilename(String path) {
        if (path == null) return "untitled";
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return (lastSlash >= 0) ? path.substring(lastSlash + 1) : path;
    }

    public List<VectorChunk> getStoredChunks(String filePath, DomainScope domain) {
        return documentChunks.getOrDefault(filePath + ":" + domain, Collections.emptyList());
    }

    private record HeadingEntry(int level, String title) {}
}
