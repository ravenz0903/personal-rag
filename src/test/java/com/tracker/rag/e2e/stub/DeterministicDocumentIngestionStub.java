package com.tracker.rag.e2e.stub;

import com.tracker.rag.e2e.contract.*;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic document ingestion stub implementing PDF page awareness,
 * Markdown heading hierarchy, SHA-256 deduplication, and transactional re-ingestion
 * per survey_report.md § 1.1 and PROJECT.md F19-F23.
 */
public class DeterministicDocumentIngestionStub implements DocumentIngestionService {

    private final VectorStore vectorStore;
    private final Map<String, String> documentHashes = new ConcurrentHashMap<>();
    private final Map<String, List<VectorChunk>> documentChunks = new ConcurrentHashMap<>();

    public DeterministicDocumentIngestionStub(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public IngestResult ingestPdf(String filePath, DomainScope domain, byte[] pdfBytes) {
        DomainScope.validateScope(domain);
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new EmptyDocumentException("Cannot ingest empty or 0-byte PDF document: " + filePath);
        }

        String fileHash = computeSha256(pdfBytes);
        String docKey = filePath + ":" + domain;

        // Check deduplication
        if (fileHash.equals(documentHashes.get(docKey))) {
            return IngestResult.skipped(filePath, fileHash);
        }

        boolean isUpdate = documentHashes.containsKey(docKey);
        if (isUpdate) {
            // Transactional re-ingestion: remove old chunks first
            vectorStore.deleteByDocumentId(filePath);
            documentChunks.remove(docKey);
        }

        // Simulate page-aware chunking (e.g. 2 pages simulated from raw bytes content)
        String content = new String(pdfBytes, StandardCharsets.UTF_8);
        String[] pages = content.split("(?i)---PAGE_BREAK---");
        if (pages.length == 0 || (pages.length == 1 && pages[0].trim().isEmpty())) {
            throw new EmptyDocumentException("Zero extractable text found in PDF: " + filePath);
        }

        List<VectorChunk> newChunks = new ArrayList<>();
        for (int p = 0; p < pages.length; p++) {
            String pageText = pages[p].trim();
            if (!pageText.isEmpty()) {
                float[] emb = LoopbackEnforcingClientStub.generateDeterministicEmbedding(pageText);
                VectorChunk chunk = new VectorChunk(
                        UUID.randomUUID(),
                        filePath,
                        domain,
                        p + 1, // 1-indexed page number
                        null,
                        pageText,
                        emb
                );
                newChunks.add(chunk);
            }
        }

        if (newChunks.isEmpty()) {
            throw new EmptyDocumentException("Zero non-empty chunks extracted from PDF: " + filePath);
        }

        // Atomic commit
        vectorStore.saveAll(newChunks);
        documentHashes.put(docKey, fileHash);
        documentChunks.put(docKey, newChunks);

        return isUpdate
                ? IngestResult.updated(filePath, newChunks.size(), fileHash)
                : IngestResult.created(filePath, newChunks.size(), fileHash);
    }

    @Override
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

        boolean isUpdate = documentHashes.containsKey(docKey);
        if (isUpdate) {
            vectorStore.deleteByDocumentId(filePath);
            documentChunks.remove(docKey);
        }

        // Stack-based Heading Parser
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
        float[] emb = LoopbackEnforcingClientStub.generateDeterministicEmbedding(text);
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

    public List<VectorChunk> getStoredChunks(String filePath, DomainScope domain) {
        return documentChunks.getOrDefault(filePath + ":" + domain, Collections.emptyList());
    }

    private record HeadingEntry(int level, String title) {}
}
