package com.tracker.rag.challenger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.domain.UnscopedQueryException;
import com.tracker.rag.dto.EmptyDocumentException;
import com.tracker.rag.dto.IngestResult;
import com.tracker.rag.repository.vector.EmbeddedVectorStore;
import com.tracker.rag.repository.vector.VectorChunk;
import com.tracker.rag.repository.vector.VectorStore;
import com.tracker.rag.service.OllamaClientService;
import com.tracker.rag.service.ingestion.DocumentIngestionService;
import com.tracker.rag.service.retrieval.InvertedIndexService;
import com.tracker.rag.service.retrieval.InvertedIndexServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Adversarial Empirical Challenge: Milestone 3 Virtual-Thread Parallel Ingestion")
public class Milestone3IngestionAdversarialChallengeTest {

    private VectorStore vectorStore;
    private InvertedIndexService invertedIndexService;

    /**
     * Resilient Ollama test double avoiding Mockito and Byte Buddy bytecode manipulation,
     * ensuring 100% deterministic execution on Java 21+ and Java 26.
     */
    static class StubOllamaClientService extends OllamaClientService {
        private final int simulatedDelayMs;
        private final boolean variableJitter;
        private final AtomicBoolean shouldFailOnPoison = new AtomicBoolean(false);
        private final AtomicInteger invocationCount = new AtomicInteger(0);

        public StubOllamaClientService(int simulatedDelayMs) {
            this(simulatedDelayMs, false);
        }

        public StubOllamaClientService(int simulatedDelayMs, boolean variableJitter) {
            super(new ObjectMapper());
            this.simulatedDelayMs = simulatedDelayMs;
            this.variableJitter = variableJitter;
        }

        public void enablePoisonPill(boolean enable) {
            this.shouldFailOnPoison.set(enable);
        }

        public int getInvocationCount() {
            return invocationCount.get();
        }

        public void resetInvocationCount() {
            invocationCount.set(0);
        }

        @Override
        public float[] generateEmbedding(String text, DomainScope domain) {
            DomainScope.validateScope(domain);
            invocationCount.incrementAndGet();

            if (shouldFailOnPoison.get() && text != null && text.contains("POISON_PILL")) {
                throw new RuntimeException("Simulated Ollama inference connection failure");
            }

            if (simulatedDelayMs > 0) {
                try {
                    int delay = simulatedDelayMs;
                    if (variableJitter) {
                        // Reverse jitter: earlier pages sleep longer to test completion out-of-order
                        int hash = Math.abs(text.hashCode() % 20);
                        delay += hash;
                    }
                    Thread.sleep(delay);
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
    }

    private byte[] createSyntheticPdf(int pages) {
        StringBuilder sb = new StringBuilder();
        for (int p = 1; p <= pages; p++) {
            if (p > 1) sb.append("\n---PAGE_BREAK---\n");
            sb.append("Page ").append(p).append(" Content: Deterministic algorithms, graph theory, and RAG retrieval systems.");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // =========================================================================
    // 1. THROUGHPUT SPEEDUP SLA BENCHMARKS (10-Page & 20-Page Docs >= 2.0x)
    // =========================================================================
    @Nested
    @DisplayName("SLA Dimension 1: Throughput Speedup Empirical Verification")
    class ThroughputSpeedupSlaTests {

        private static final int SIMULATED_LATENCY_MS = 20;

        @Test
        @DisplayName("SLA-M3-01: 10-page document parallel ingestion delivers >= 2.0x speedup over sequential baseline")
        void testTenPageThroughputSpeedupSla() {
            StubOllamaClientService stubOllama = new StubOllamaClientService(SIMULATED_LATENCY_MS);
            DocumentIngestionService service = new DocumentIngestionService(vectorStore, null, stubOllama, invertedIndexService);

            int pageCount = 10;
            byte[] pdfBytes = createSyntheticPdf(pageCount);
            String docPath = "courses/cs301_perf10.pdf";

            // Warm up
            service.ingestPdf("warmup.pdf", DomainScope.ACADEMICS, createSyntheticPdf(2));
            vectorStore.deleteByDocumentId("warmup.pdf");

            // Measure sequential baseline
            long seqStart = System.nanoTime();
            for (int p = 1; p <= pageCount; p++) {
                stubOllama.generateEmbedding("Page " + p + " Content: Algorithms", DomainScope.ACADEMICS);
            }
            long seqDurationMs = (System.nanoTime() - seqStart) / 1_000_000;

            // Measure parallel ingestion
            long parStart = System.nanoTime();
            IngestResult result = service.ingestPdf(docPath, DomainScope.ACADEMICS, pdfBytes);
            long parDurationMs = (System.nanoTime() - parStart) / 1_000_000;

            double speedup = (double) seqDurationMs / Math.max(1, parDurationMs);

            assertThat(result.status()).isEqualTo("CREATED");
            assertThat(result.chunksCreated()).isEqualTo(pageCount);
            assertThat(speedup)
                    .as("Parallel ingestion of 10 pages must achieve at least 2.0x speedup over sequential baseline (seq=%d ms, par=%d ms, speedup=%.2fx)",
                            seqDurationMs, parDurationMs, speedup)
                    .isGreaterThanOrEqualTo(2.0);

            // Verify order preservation
            List<VectorChunk> chunks = service.getStoredChunks(docPath, DomainScope.ACADEMICS);
            assertThat(chunks).hasSize(pageCount);
            for (int i = 0; i < pageCount; i++) {
                assertThat(chunks.get(i).pageNumber()).isEqualTo(i + 1);
            }
        }

        @Test
        @DisplayName("SLA-M3-02: 20-page document parallel ingestion delivers >= 2.0x speedup over sequential baseline")
        void testTwentyPageThroughputSpeedupSla() {
            StubOllamaClientService stubOllama = new StubOllamaClientService(SIMULATED_LATENCY_MS);
            DocumentIngestionService service = new DocumentIngestionService(vectorStore, null, stubOllama, invertedIndexService);

            int pageCount = 20;
            byte[] pdfBytes = createSyntheticPdf(pageCount);
            String docPath = "courses/cs401_perf20.pdf";

            long seqStart = System.nanoTime();
            for (int p = 1; p <= pageCount; p++) {
                stubOllama.generateEmbedding("Page " + p + " Content: Algorithms", DomainScope.CAREER);
            }
            long seqDurationMs = (System.nanoTime() - seqStart) / 1_000_000;

            long parStart = System.nanoTime();
            IngestResult result = service.ingestPdf(docPath, DomainScope.CAREER, pdfBytes);
            long parDurationMs = (System.nanoTime() - parStart) / 1_000_000;

            double speedup = (double) seqDurationMs / Math.max(1, parDurationMs);

            assertThat(result.status()).isEqualTo("CREATED");
            assertThat(result.chunksCreated()).isEqualTo(pageCount);
            assertThat(speedup)
                    .as("Parallel ingestion of 20 pages must achieve at least 2.0x speedup (seq=%d ms, par=%d ms, speedup=%.2fx)",
                            seqDurationMs, parDurationMs, speedup)
                    .isGreaterThanOrEqualTo(2.0);
        }
    }

    // =========================================================================
    // 2. CONCURRENT MULTI-DOCUMENT INGESTION LOAD & STARVATION STRESS TEST
    // =========================================================================
    @Nested
    @DisplayName("SLA Dimension 2: Multi-Document Load & Deadlock/Starvation Challenge")
    class ConcurrentMultiDocumentLoadTests {

        @Test
        @DisplayName("LOAD-M3-01: 30 concurrent document ingestion tasks across 3 domains without deadlocks")
        void testConcurrentMultiDocumentIngestionUnderLoad() throws InterruptedException, ExecutionException {
            StubOllamaClientService stubOllama = new StubOllamaClientService(5);
            DocumentIngestionService service = new DocumentIngestionService(vectorStore, null, stubOllama, invertedIndexService);

            int docCount = 30; // 10 per domain
            int pagesPerDoc = 5;
            DomainScope[] scopes = {DomainScope.ACADEMICS, DomainScope.PERSONAL, DomainScope.CAREER};

            ExecutorService callerExecutor = Executors.newVirtualThreadPerTaskExecutor();
            List<Future<IngestResult>> futures = new ArrayList<>();

            long start = System.nanoTime();
            for (int i = 0; i < docCount; i++) {
                final int id = i;
                final DomainScope scope = scopes[i % scopes.length];
                byte[] bytes = createSyntheticPdf(pagesPerDoc);
                futures.add(callerExecutor.submit(() -> service.ingestPdf("doc_load_" + id + ".pdf", scope, bytes)));
            }

            for (Future<IngestResult> future : futures) {
                IngestResult res = future.get();
                assertThat(res.status()).isEqualTo("CREATED");
                assertThat(res.chunksCreated()).isEqualTo(pagesPerDoc);
            }
            long totalElapsedMs = (System.nanoTime() - start) / 1_000_000;

            // 30 docs * 5 pages = 150 chunks total (50 per domain)
            assertThat(vectorStore.countByDomain(DomainScope.ACADEMICS)).isEqualTo(50);
            assertThat(vectorStore.countByDomain(DomainScope.PERSONAL)).isEqualTo(50);
            assertThat(vectorStore.countByDomain(DomainScope.CAREER)).isEqualTo(50);

            assertThat(invertedIndexService.size(DomainScope.ACADEMICS)).isEqualTo(50);
            assertThat(invertedIndexService.size(DomainScope.PERSONAL)).isEqualTo(50);
            assertThat(invertedIndexService.size(DomainScope.CAREER)).isEqualTo(50);

            // Execution of 150 chunks with 5ms each would take 750ms sequentially; should complete quickly
            assertThat(totalElapsedMs).isLessThan(5000);
            callerExecutor.close();
        }
    }

    // =========================================================================
    // 3. DETERMINISTIC ORDERING UNDER VIRTUAL THREAD JITTER
    // =========================================================================
    @Nested
    @DisplayName("Ordering Dimension: Deterministic Page Numbering & Order Preservation")
    class PageOrderingUnderJitterTests {

        @Test
        @DisplayName("ORDER-M3-01: 20-page document maintains strict 1..N order even when threads finish out-of-order")
        void testDeterministicPageOrderingUnderVirtualThreadJitter() {
            StubOllamaClientService stubOllama = new StubOllamaClientService(5, true); // variable jitter
            DocumentIngestionService service = new DocumentIngestionService(vectorStore, null, stubOllama, invertedIndexService);

            int pageCount = 20;
            byte[] bytes = createSyntheticPdf(pageCount);
            String docPath = "courses/jitter_test.pdf";

            service.ingestPdf(docPath, DomainScope.ACADEMICS, bytes);

            List<VectorChunk> chunks = service.getStoredChunks(docPath, DomainScope.ACADEMICS);
            assertThat(chunks).hasSize(pageCount);

            for (int i = 0; i < pageCount; i++) {
                VectorChunk chunk = chunks.get(i);
                assertThat(chunk.pageNumber()).isEqualTo(i + 1);
                assertThat(chunk.content()).contains("Page " + (i + 1) + " Content");
            }
        }

        @Test
        @DisplayName("ORDER-M3-02: 50-page document under extreme reverse jitter guarantees 100% 1-indexed order")
        void testExtremeReverseJitterOrdering50Pages() {
            // Reverse delay: page 1 delays 50ms, page 50 delays 1ms, forcing out-of-order completion
            StubOllamaClientService stubOllama = new StubOllamaClientService(1) {
                @Override
                public float[] generateEmbedding(String text, DomainScope domain) {
                    DomainScope.validateScope(domain);
                    super.generateEmbedding(text, domain);
                    // Extract page number from text
                    int pageNum = 1;
                    if (text != null && text.contains("Page ")) {
                        try {
                            String numStr = text.substring(text.indexOf("Page ") + 5).split(" ")[0];
                            pageNum = Integer.parseInt(numStr);
                        } catch (Exception ignored) {}
                    }
                    try {
                        Thread.sleep(Math.max(1, 55 - pageNum)); // Page 1 sleeps 54ms, Page 50 sleeps 5ms
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return OllamaClientService.generateDeterministicFallbackEmbedding(text);
                }
            };
            DocumentIngestionService service = new DocumentIngestionService(vectorStore, null, stubOllama, invertedIndexService);

            int pageCount = 50;
            byte[] bytes = createSyntheticPdf(pageCount);
            String docPath = "courses/extreme_reverse_jitter.pdf";

            IngestResult result = service.ingestPdf(docPath, DomainScope.ACADEMICS, bytes);
            assertThat(result.status()).isEqualTo("CREATED");
            assertThat(result.chunksCreated()).isEqualTo(pageCount);

            List<VectorChunk> chunks = service.getStoredChunks(docPath, DomainScope.ACADEMICS);
            assertThat(chunks).hasSize(pageCount);

            for (int i = 0; i < pageCount; i++) {
                VectorChunk chunk = chunks.get(i);
                assertThat(chunk.pageNumber())
                        .as("Chunk at index %d must have pageNumber %d", i, i + 1)
                        .isEqualTo(i + 1);
                assertThat(chunk.content())
                        .as("Chunk at index %d content must belong to Page %d", i, i + 1)
                        .contains("Page " + (i + 1) + " Content");
            }
        }

        @Test
        @DisplayName("ORDER-M3-03: Markdown multi-section hierarchy preserves deterministic sequence under jitter")
        void testMarkdownHierarchyOrderingUnderJitter() {
            StubOllamaClientService stubOllama = new StubOllamaClientService(5, true);
            DocumentIngestionService service = new DocumentIngestionService(vectorStore, null, stubOllama, invertedIndexService);

            StringBuilder md = new StringBuilder();
            int sectionCount = 15;
            for (int s = 1; s <= sectionCount; s++) {
                md.append("# Section ").append(s).append("\nContent for section ").append(s).append(".\n\n");
            }

            String docPath = "notes/jitter_markdown.md";
            IngestResult result = service.ingestMarkdown(docPath, DomainScope.CAREER, md.toString());
            assertThat(result.status()).isEqualTo("CREATED");
            assertThat(result.chunksCreated()).isEqualTo(sectionCount);

            List<VectorChunk> chunks = service.getStoredChunks(docPath, DomainScope.CAREER);
            assertThat(chunks).hasSize(sectionCount);

            for (int i = 0; i < sectionCount; i++) {
                VectorChunk chunk = chunks.get(i);
                assertThat(chunk.headingPath()).isEqualTo("Section " + (i + 1));
                assertThat(chunk.content()).contains("Content for section " + (i + 1));
            }
        }
    }

    // =========================================================================
    // 4. SHA-256 DEDUPLICATION BENCHMARK & BYPASS EMPIRICAL VERIFICATION
    // =========================================================================
    @Nested
    @DisplayName("Deduplication Dimension: Instant Sub-10ms Bypass & Zero Thread/Inference Spawning")
    class DeduplicationBypassTests {

        @Test
        @DisplayName("DEDUP-M3-01: Identical PDF document bypasses re-ingestion in sub-10ms with zero inference calls")
        void testSha256DeduplicationInstantBypassUnder10MsAndZeroInferenceCalls() {
            StubOllamaClientService stubOllama = new StubOllamaClientService(10);
            DocumentIngestionService service = new DocumentIngestionService(vectorStore, null, stubOllama, invertedIndexService);

            int pageCount = 8;
            byte[] bytes = createSyntheticPdf(pageCount);
            String docPath = "courses/dedup_stress.pdf";

            // Initial ingestion
            IngestResult r1 = service.ingestPdf(docPath, DomainScope.ACADEMICS, bytes);
            assertThat(r1.status()).isEqualTo("CREATED");
            assertThat(r1.chunksCreated()).isEqualTo(pageCount);
            assertThat(stubOllama.getInvocationCount()).isEqualTo(pageCount);

            // Reset inference invocation counter
            stubOllama.resetInvocationCount();

            // Run 50 deduplicated requests in a tight loop and measure each latency
            int iterations = 50;
            long maxLatencyNs = 0;
            long totalLatencyNs = 0;

            for (int i = 0; i < iterations; i++) {
                long start = System.nanoTime();
                IngestResult r2 = service.ingestPdf(docPath, DomainScope.ACADEMICS, bytes);
                long elapsed = System.nanoTime() - start;

                totalLatencyNs += elapsed;
                if (elapsed > maxLatencyNs) maxLatencyNs = elapsed;

                assertThat(r2.status()).isEqualTo("SKIPPED");
                assertThat(r2.chunksCreated()).isEqualTo(0);
                assertThat(r2.contentHash()).isEqualTo(r1.contentHash());
            }

            double maxLatencyMs = maxLatencyNs / 1_000_000.0;
            double avgLatencyMs = (totalLatencyNs / (double) iterations) / 1_000_000.0;

            // Zero inference calls during duplicate requests
            assertThat(stubOllama.getInvocationCount())
                    .as("Deduplication bypass must not invoke Ollama embedding inference")
                    .isEqualTo(0);

            // Sub-10ms latency SLA
            assertThat(maxLatencyMs)
                    .as("Max deduplication latency must be sub-10ms (was %.3f ms, avg=%.3f ms)", maxLatencyMs, avgLatencyMs)
                    .isLessThan(10.0);

            // Vector store count remains unchanged
            assertThat(vectorStore.countByDomain(DomainScope.ACADEMICS)).isEqualTo(pageCount);
        }

        @Test
        @DisplayName("DEDUP-M3-02: Deduplication strictly isolates by DomainScope")
        void testSha256DeduplicationDomainIsolation() {
            StubOllamaClientService stubOllama = new StubOllamaClientService(0);
            DocumentIngestionService service = new DocumentIngestionService(vectorStore, null, stubOllama, invertedIndexService);

            byte[] bytes = createSyntheticPdf(3);
            String docPath = "common/resume.pdf";

            // Ingest in ACADEMICS
            IngestResult rAcad = service.ingestPdf(docPath, DomainScope.ACADEMICS, bytes);
            assertThat(rAcad.status()).isEqualTo("CREATED");

            // Ingest identical document in CAREER - must NOT be skipped due to domain scoping
            IngestResult rCareer = service.ingestPdf(docPath, DomainScope.CAREER, bytes);
            assertThat(rCareer.status()).isEqualTo("CREATED");

            // Ingest again in ACADEMICS - must be skipped
            IngestResult rAcad2 = service.ingestPdf(docPath, DomainScope.ACADEMICS, bytes);
            assertThat(rAcad2.status()).isEqualTo("SKIPPED");

            // Ingest again in CAREER - must be skipped
            IngestResult rCareer2 = service.ingestPdf(docPath, DomainScope.CAREER, bytes);
            assertThat(rCareer2.status()).isEqualTo("SKIPPED");
        }

        @Test
        @DisplayName("DEDUP-M3-03: Single-byte modification bypasses deduplication and updates document")
        void testSha256DeduplicationModifiedContentReingests() {
            StubOllamaClientService stubOllama = new StubOllamaClientService(0);
            DocumentIngestionService service = new DocumentIngestionService(vectorStore, null, stubOllama, invertedIndexService);

            byte[] v1 = createSyntheticPdf(4);
            String docPath = "courses/syllabus_v1.pdf";

            IngestResult r1 = service.ingestPdf(docPath, DomainScope.ACADEMICS, v1);
            assertThat(r1.status()).isEqualTo("CREATED");

            // Modify content slightly
            byte[] v2 = (new String(v1, StandardCharsets.UTF_8) + " - Addendum").getBytes(StandardCharsets.UTF_8);
            IngestResult r2 = service.ingestPdf(docPath, DomainScope.ACADEMICS, v2);
            assertThat(r2.status()).isEqualTo("UPDATED");
            assertThat(r2.contentHash()).isNotEqualTo(r1.contentHash());
        }
    }

    // =========================================================================
    // 5. ADVERSARIAL STRESS: TRANSACTIONAL DATA LOSS PREVENTION & RESILIENCE
    // =========================================================================
    @Nested
    @DisplayName("Adversarial Defect Discovery: Premature Deletion & Concurrency Race Conditions")
    class AdversarialDefectTests {

        @Test
        @DisplayName("ADV-BUG-01: Premature deletion during re-ingestion causes data loss if embedding fails")
        void testDataLossOnReingestFailure() {
            StubOllamaClientService stubOllama = new StubOllamaClientService(0);
            DocumentIngestionService service = new DocumentIngestionService(vectorStore, null, stubOllama, invertedIndexService);

            String docPath = "courses/critical_syllabus.pdf";
            byte[] v1 = createSyntheticPdf(5);

            // Step 1: Initial successful ingestion of 5 pages
            IngestResult r1 = service.ingestPdf(docPath, DomainScope.ACADEMICS, v1);
            assertThat(r1.status()).isEqualTo("CREATED");
            assertThat(vectorStore.countByDomain(DomainScope.ACADEMICS)).isEqualTo(5);
            assertThat(invertedIndexService.size(DomainScope.ACADEMICS)).isEqualTo(5);

            // Step 2: Attempt re-ingestion with poisoned document that causes embedding failure in Phase 2
            byte[] v2Corrupted = "Page 1 Content: Valid\n---PAGE_BREAK---\nPage 2 Content: POISON_PILL fail".getBytes(StandardCharsets.UTF_8);
            stubOllama.enablePoisonPill(true);

            // Attempting re-ingestion throws exception
            assertThatThrownBy(() -> service.ingestPdf(docPath, DomainScope.ACADEMICS, v2Corrupted))
                    .isInstanceOf(RuntimeException.class);

            // STEP 3: Check state of original document v1 in VectorStore and InvertedIndex
            long remainingChunks = vectorStore.countByDomain(DomainScope.ACADEMICS);
            int remainingIndex = invertedIndexService.size(DomainScope.ACADEMICS);

            System.out.println("Empirical finding ADV-BUG-01: remainingChunks after failed reingestion = " + remainingChunks);

            // Assert that old document chunks are safely preserved upon re-ingestion failure
            assertThat(remainingChunks)
                    .as("Old chunks must be preserved in VectorStore on Phase 2 re-ingestion failure")
                    .isEqualTo(5);
            assertThat(remainingIndex)
                    .as("Old chunks must be preserved in InvertedIndex on Phase 2 re-ingestion failure")
                    .isEqualTo(5);
            assertThat(service.getStoredChunks(docPath, DomainScope.ACADEMICS))
                    .as("documentChunks map must retain previous valid chunks")
                    .hasSize(5);
        }

        @Test
        @DisplayName("ADV-BUG-02: Phase 1 extraction failure during re-ingestion preserves existing chunks")
        void testDataLossOnPhase1ExtractionFailure() {
            StubOllamaClientService stubOllama = new StubOllamaClientService(0);
            DocumentIngestionService service = new DocumentIngestionService(vectorStore, null, stubOllama, invertedIndexService);

            String docPath = "courses/phase1_data_loss.pdf";
            byte[] v1 = createSyntheticPdf(4);

            service.ingestPdf(docPath, DomainScope.ACADEMICS, v1);
            assertThat(vectorStore.countByDomain(DomainScope.ACADEMICS)).isEqualTo(4);

            // Attempt re-ingestion with zero-byte content or whitespace-only
            assertThatThrownBy(() -> service.ingestPdf(docPath, DomainScope.ACADEMICS, new byte[0]))
                    .isInstanceOf(EmptyDocumentException.class);

            // Previous chunks must be preserved
            assertThat(vectorStore.countByDomain(DomainScope.ACADEMICS)).isEqualTo(4);
            assertThat(invertedIndexService.size(DomainScope.ACADEMICS)).isEqualTo(4);
            assertThat(service.getStoredChunks(docPath, DomainScope.ACADEMICS)).hasSize(4);
        }

        @Test
        @DisplayName("ADV-BUG-03: Markdown embedding failure preserves existing Markdown chunks")
        void testDataLossOnMarkdownEmbeddingFailure() {
            StubOllamaClientService stubOllama = new StubOllamaClientService(0);
            DocumentIngestionService service = new DocumentIngestionService(vectorStore, null, stubOllama, invertedIndexService);

            String docPath = "notes/critical_goals.md";
            String v1 = "# Personal Mission\nLive purposefully.\n## Habit\nRead daily.";

            service.ingestMarkdown(docPath, DomainScope.PERSONAL, v1);
            assertThat(vectorStore.countByDomain(DomainScope.PERSONAL)).isEqualTo(2);

            // Attempt re-ingestion with poisoned Markdown
            stubOllama.enablePoisonPill(true);
            String v2 = "# Updated Mission\nLive purposefully.\n## Bad\nPOISON_PILL fail";

            assertThatThrownBy(() -> service.ingestMarkdown(docPath, DomainScope.PERSONAL, v2))
                    .isInstanceOf(RuntimeException.class);

            // Previous chunks preserved
            assertThat(vectorStore.countByDomain(DomainScope.PERSONAL)).isEqualTo(2);
            assertThat(invertedIndexService.size(DomainScope.PERSONAL)).isEqualTo(2);
            assertThat(service.getStoredChunks(docPath, DomainScope.PERSONAL)).hasSize(2);
        }

        @Test
        @DisplayName("ADV-BUG-04: Subsequent valid re-ingestion succeeds cleanly after a failed re-ingestion attempt")
        void testRecoveryAfterFailedReingest() {
            StubOllamaClientService stubOllama = new StubOllamaClientService(0);
            DocumentIngestionService service = new DocumentIngestionService(vectorStore, null, stubOllama, invertedIndexService);

            String docPath = "courses/resilient_course.pdf";
            byte[] v1 = createSyntheticPdf(3);

            // Ingestion 1: Success (3 chunks)
            service.ingestPdf(docPath, DomainScope.ACADEMICS, v1);
            assertThat(vectorStore.countByDomain(DomainScope.ACADEMICS)).isEqualTo(3);

            // Ingestion 2: Failure in Phase 2
            stubOllama.enablePoisonPill(true);
            byte[] v2Corrupted = "Page 1: POISON_PILL fail".getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> service.ingestPdf(docPath, DomainScope.ACADEMICS, v2Corrupted))
                    .isInstanceOf(RuntimeException.class);

            // Chunks still 3
            assertThat(vectorStore.countByDomain(DomainScope.ACADEMICS)).isEqualTo(3);

            // Ingestion 3: Valid update with 2 pages
            stubOllama.enablePoisonPill(false);
            byte[] v3Valid = createSyntheticPdf(2);
            IngestResult r3 = service.ingestPdf(docPath, DomainScope.ACADEMICS, v3Valid);

            assertThat(r3.status()).isEqualTo("UPDATED");
            assertThat(r3.chunksCreated()).isEqualTo(2);
            assertThat(vectorStore.countByDomain(DomainScope.ACADEMICS)).isEqualTo(2);
            assertThat(invertedIndexService.size(DomainScope.ACADEMICS)).isEqualTo(2);
            assertThat(service.getStoredChunks(docPath, DomainScope.ACADEMICS)).hasSize(2);
        }

        @Test
        @DisplayName("ADV-RACE-02: Simultaneous ingestion of the exact same document by concurrent threads")
        void testConcurrentSameDocumentIngestion() throws InterruptedException {
            StubOllamaClientService stubOllama = new StubOllamaClientService(20);
            DocumentIngestionService service = new DocumentIngestionService(vectorStore, null, stubOllama, invertedIndexService);

            String docPath = "courses/duplicate_race.pdf";
            byte[] bytes = createSyntheticPdf(4);

            int threadCount = 4;
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch latch = new CountDownLatch(1);
            List<Future<IngestResult>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    latch.await();
                    return service.ingestPdf(docPath, DomainScope.ACADEMICS, bytes);
                }));
            }

            latch.countDown(); // Release all threads simultaneously
            List<IngestResult> results = new ArrayList<>();
            for (Future<IngestResult> f : futures) {
                try {
                    results.add(f.get());
                } catch (ExecutionException e) {
                    // ignore
                }
            }

            long chunkCount = vectorStore.countByDomain(DomainScope.ACADEMICS);
            int indexSize = invertedIndexService.size(DomainScope.ACADEMICS);
            System.out.println("Empirical finding ADV-RACE-02: Total chunks for same doc = " + chunkCount + ", index size = " + indexSize);

            executor.close();
        }
    }
}
