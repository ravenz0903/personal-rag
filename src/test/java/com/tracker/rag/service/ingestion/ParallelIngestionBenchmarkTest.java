package com.tracker.rag.service.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.dto.IngestResult;
import com.tracker.rag.repository.vector.EmbeddedVectorStore;
import com.tracker.rag.repository.vector.VectorChunk;
import com.tracker.rag.repository.vector.VectorStore;
import com.tracker.rag.service.OllamaClientService;
import com.tracker.rag.service.retrieval.InvertedIndexService;
import com.tracker.rag.service.retrieval.InvertedIndexServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Milestone 3: Parallel Virtual-Thread Ingestion Throughput & Invariant Benchmark")
public class ParallelIngestionBenchmarkTest {

    private VectorStore vectorStore;
    private InvertedIndexService invertedIndexService;
    private OllamaClientService mockOllamaClient;
    private DocumentIngestionService ingestionService;

    private static final int SIMULATED_EMBED_LATENCY_MS = 20; // Realistic Ollama inference time

    private static class LatencySimulatingOllamaClient extends OllamaClientService {
        private final long delayMs;

        public LatencySimulatingOllamaClient(long delayMs) {
            super(new ObjectMapper());
            this.delayMs = delayMs;
        }

        @Override
        public float[] generateEmbedding(String text, DomainScope domain) {
            DomainScope.validateScope(domain);
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
            return OllamaClientService.generateDeterministicFallbackEmbedding(text);
        }
    }

    @BeforeEach
    void setUp() {
        vectorStore = new EmbeddedVectorStore();
        invertedIndexService = new InvertedIndexServiceImpl();
        mockOllamaClient = new LatencySimulatingOllamaClient(SIMULATED_EMBED_LATENCY_MS);
        ingestionService = new DocumentIngestionService(vectorStore, null, mockOllamaClient, invertedIndexService);

        warmupJvm();
    }

    private void warmupJvm() {
        // Warm up sequential embedding
        for (int i = 0; i < 2; i++) {
            mockOllamaClient.generateEmbedding("Warmup text " + i, DomainScope.ACADEMICS);
        }

        // Warm up parallel virtual thread ingestion pipeline
        byte[] warmupBytes = createSyntheticMultiPagePdf(2);
        String warmupDoc = "warmup.pdf";
        ingestionService.ingestPdf(warmupDoc, DomainScope.ACADEMICS, warmupBytes);
        vectorStore.deleteByDocumentId(warmupDoc);
        if (invertedIndexService != null) {
            invertedIndexService.removeDocument(warmupDoc, DomainScope.ACADEMICS);
        }
    }

    private byte[] createSyntheticMultiPagePdf(int pageCount) {
        StringBuilder sb = new StringBuilder();
        for (int p = 1; p <= pageCount; p++) {
            if (p > 1) sb.append("\n---PAGE_BREAK---\n");
            sb.append("Page ").append(p).append(" Content: Algorithms, data structures, and computational complexity analysis lecture notes.");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("BENCH-M3-01: 10-page document parallel ingestion delivers >= 2.0x throughput speedup over sequential baseline")
    void testTenPageThroughputSpeedup() {
        int pageCount = 10;
        byte[] pdfBytes = createSyntheticMultiPagePdf(pageCount);
        String docPath = "courses/cs301_lecture10.pdf";

        // Measure actual sequential baseline on identical page count
        long seqStart = System.nanoTime();
        for (int p = 1; p <= pageCount; p++) {
            mockOllamaClient.generateEmbedding("Page " + p + " Content: Algorithms, data structures, and computational complexity analysis lecture notes.", DomainScope.ACADEMICS);
        }
        long measuredSequentialMs = (System.nanoTime() - seqStart) / 1_000_000;

        // Measure parallel ingestion
        long parStart = System.nanoTime();
        IngestResult result = ingestionService.ingestPdf(docPath, DomainScope.ACADEMICS, pdfBytes);
        long measuredParallelMs = (System.nanoTime() - parStart) / 1_000_000;

        double speedup = (double) measuredSequentialMs / Math.max(1, measuredParallelMs);

        // Correctness assertions
        assertThat(result.status()).isEqualTo("CREATED");
        assertThat(result.chunksCreated()).isEqualTo(pageCount);

        List<VectorChunk> storedChunks = ingestionService.getStoredChunks(docPath, DomainScope.ACADEMICS);
        assertThat(storedChunks).hasSize(pageCount);
        for (int i = 0; i < pageCount; i++) {
            assertThat(storedChunks.get(i).pageNumber()).isEqualTo(i + 1);
        }

        // Empirical speedup assertion: >= 2.0x requirement
        assertThat(speedup)
                .as("Parallel ingestion of 10 pages must achieve at least 2.0x speedup over sequential baseline (seq=%d ms, par=%d ms, speedup=%.2fx)",
                        measuredSequentialMs, measuredParallelMs, speedup)
                .isGreaterThanOrEqualTo(2.0);
    }

    @Test
    @DisplayName("BENCH-M3-02: 20-page document parallel ingestion delivers >= 2.0x throughput speedup")
    void testTwentyPageThroughputSpeedup() {
        int pageCount = 20;
        byte[] pdfBytes = createSyntheticMultiPagePdf(pageCount);
        String docPath = "courses/cs401_advanced_rag.pdf";

        // Measure actual sequential baseline
        long seqStart = System.nanoTime();
        for (int p = 1; p <= pageCount; p++) {
            mockOllamaClient.generateEmbedding("Page " + p + " Content: Algorithms, data structures, and computational complexity analysis lecture notes.", DomainScope.ACADEMICS);
        }
        long measuredSequentialMs = (System.nanoTime() - seqStart) / 1_000_000;

        // Measure parallel ingestion
        long parStart = System.nanoTime();
        IngestResult result = ingestionService.ingestPdf(docPath, DomainScope.ACADEMICS, pdfBytes);
        long measuredParallelMs = (System.nanoTime() - parStart) / 1_000_000;

        double speedup = (double) measuredSequentialMs / Math.max(1, measuredParallelMs);

        assertThat(result.status()).isEqualTo("CREATED");
        assertThat(result.chunksCreated()).isEqualTo(pageCount);

        List<VectorChunk> storedChunks = ingestionService.getStoredChunks(docPath, DomainScope.ACADEMICS);
        assertThat(storedChunks).hasSize(pageCount);
        for (int i = 0; i < pageCount; i++) {
            assertThat(storedChunks.get(i).pageNumber()).isEqualTo(i + 1);
        }

        assertThat(speedup)
                .as("Parallel ingestion of 20 pages must achieve at least 2.0x speedup (seq=%d ms, par=%d ms, speedup=%.2fx)",
                        measuredSequentialMs, measuredParallelMs, speedup)
                .isGreaterThanOrEqualTo(2.0);
    }

    @Test
    @DisplayName("BENCH-M3-03: SHA-256 deduplication skips re-ingestion in sub-10ms without spawning virtual threads")
    void testDeduplicationSub10msBypass() {
        byte[] pdfBytes = createSyntheticMultiPagePdf(10);
        String docPath = "courses/dedup_test.pdf";

        // Initial ingestion
        ingestionService.ingestPdf(docPath, DomainScope.CAREER, pdfBytes);

        // Timed duplicate ingestion
        long start = System.nanoTime();
        IngestResult duplicateResult = ingestionService.ingestPdf(docPath, DomainScope.CAREER, pdfBytes);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(duplicateResult.status()).isEqualTo("SKIPPED");
        assertThat(duplicateResult.chunksCreated()).isEqualTo(0);
        assertThat(elapsedMs).isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("BENCH-M3-04: Transactional update replaces old chunks and synchronizes InvertedIndexService")
    void testTransactionalReingestionPreservesInvertedIndex() {
        byte[] v1 = createSyntheticMultiPagePdf(5);
        String docPath = "courses/transactional_doc.pdf";

        IngestResult r1 = ingestionService.ingestPdf(docPath, DomainScope.ACADEMICS, v1);
        assertThat(r1.status()).isEqualTo("CREATED");
        assertThat(vectorStore.countByDomain(DomainScope.ACADEMICS)).isEqualTo(5);
        assertThat(invertedIndexService.size(DomainScope.ACADEMICS)).isEqualTo(5);

        // Updated 3-page document
        byte[] v2 = createSyntheticMultiPagePdf(3);
        IngestResult r2 = ingestionService.ingestPdf(docPath, DomainScope.ACADEMICS, v2);
        assertThat(r2.status()).isEqualTo("UPDATED");
        assertThat(vectorStore.countByDomain(DomainScope.ACADEMICS)).isEqualTo(3);
        assertThat(invertedIndexService.size(DomainScope.ACADEMICS)).isEqualTo(3);
    }
}
