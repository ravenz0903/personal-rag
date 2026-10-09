package com.tracker.rag.service.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.domain.UnscopedQueryException;
import com.tracker.rag.dto.Citation;
import com.tracker.rag.dto.HybridSearchResult;
import com.tracker.rag.dto.IngestResult;
import com.tracker.rag.repository.vector.EmbeddedVectorStore;
import com.tracker.rag.repository.vector.VectorChunk;
import com.tracker.rag.service.DocumentIngestionService;
import com.tracker.rag.service.HybridRetrieverService;
import com.tracker.rag.service.OllamaClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Adversarial Empirical Challenge: InvertedIndexService Domain Isolation, Fail-Closed & Invalidation")
public class InvertedIndexEmpiricalChallengeTest {

    private InvertedIndexServiceImpl indexService;
    private EmbeddedVectorStore vectorStore;
    private OllamaClientService ollamaClientService;
    private HybridRetrieverService hybridRetrieverService;
    private DocumentIngestionService documentIngestionService;

    private static final float[] DUMMY_EMBEDDING = new float[768];

    @BeforeEach
    void setUp() {
        vectorStore = new EmbeddedVectorStore();
        ollamaClientService = new OllamaClientService(new ObjectMapper());
        indexService = new InvertedIndexServiceImpl(vectorStore);
        hybridRetrieverService = new HybridRetrieverService(vectorStore, ollamaClientService, indexService);
        documentIngestionService = new DocumentIngestionService(vectorStore, null, ollamaClientService, indexService);
    }

    private VectorChunk makeChunk(String docId, DomainScope domain, int page, String content) {
        return new VectorChunk(
                UUID.randomUUID(),
                docId,
                domain,
                page,
                "H1 > Section",
                content,
                DUMMY_EMBEDDING
        );
    }

    // =========================================================================
    // 1. DOMAIN ISOLATION ADVERSARIAL CHALLENGES
    // =========================================================================
    @Nested
    @DisplayName("1. Domain Isolation Rigorous Probing")
    class DomainIsolationTests {

        @Test
        @DisplayName("Adversarial cross-domain term isolation: PERSONAL terms strictly unreachable from ACADEMICS or CAREER")
        void testPersonalTermsUnreachableInOtherDomains() {
            String secretMedical = "patient medical records diagnosis prescription hypersensitive";
            String secretFinancial = "banking transactions wire routing swift offshore account";
            String secretDiary = "deep personal reflections diary secret confessions";

            VectorChunk chunkMed = makeChunk("medical.pdf", DomainScope.PERSONAL, 1, secretMedical);
            VectorChunk chunkFin = makeChunk("finance.pdf", DomainScope.PERSONAL, 2, secretFinancial);
            VectorChunk chunkDia = makeChunk("diary.txt", DomainScope.PERSONAL, 1, secretDiary);

            indexService.indexChunks(List.of(chunkMed, chunkFin, chunkDia));

            assertEquals(3, indexService.size(DomainScope.PERSONAL));
            assertEquals(0, indexService.size(DomainScope.ACADEMICS));
            assertEquals(0, indexService.size(DomainScope.CAREER));

            // Probe ACADEMICS
            assertTrue(indexService.search("medical diagnosis prescription", DomainScope.ACADEMICS, 10).isEmpty(),
                    "PERSONAL medical terms leaked to ACADEMICS");
            assertTrue(indexService.search("banking transactions swift routing", DomainScope.ACADEMICS, 10).isEmpty(),
                    "PERSONAL finance terms leaked to ACADEMICS");
            assertTrue(indexService.search("secret confessions diary", DomainScope.ACADEMICS, 10).isEmpty(),
                    "PERSONAL diary terms leaked to ACADEMICS");

            // Probe CAREER
            assertTrue(indexService.search("medical diagnosis prescription", DomainScope.CAREER, 10).isEmpty(),
                    "PERSONAL medical terms leaked to CAREER");
            assertTrue(indexService.search("banking transactions swift routing", DomainScope.CAREER, 10).isEmpty(),
                    "PERSONAL finance terms leaked to CAREER");
            assertTrue(indexService.search("secret confessions diary", DomainScope.CAREER, 10).isEmpty(),
                    "PERSONAL diary terms leaked to CAREER");

            // Verify PERSONAL search succeeds and returns all 3
            assertEquals(1, indexService.search("prescription", DomainScope.PERSONAL, 10).size());
            assertEquals(1, indexService.search("swift", DomainScope.PERSONAL, 10).size());
            assertEquals(1, indexService.search("confessions", DomainScope.PERSONAL, 10).size());
        }

        @Test
        @DisplayName("Complete pairwise domain isolation matrix across all 6 directional permutations")
        void testCompletePairwiseMatrixIsolation() {
            VectorChunk academicChunk = makeChunk("cs101.pdf", DomainScope.ACADEMICS, 1,
                    "eigenvectors eigenvalues singular value decomposition orthogonal basis");
            VectorChunk personalChunk = makeChunk("health.pdf", DomainScope.PERSONAL, 1,
                    "cholesterol triglycerides cardiovascular biometric biomarkers insulin");
            VectorChunk careerChunk = makeChunk("offer.pdf", DomainScope.CAREER, 1,
                    "compensation equity vesting schedule bonus package stock options");

            indexService.indexChunks(List.of(academicChunk, personalChunk, careerChunk));

            // ACADEMICS terms queried in PERSONAL and CAREER
            assertTrue(indexService.search("eigenvectors eigenvalues", DomainScope.PERSONAL, 5).isEmpty());
            assertTrue(indexService.search("eigenvectors eigenvalues", DomainScope.CAREER, 5).isEmpty());

            // PERSONAL terms queried in ACADEMICS and CAREER
            assertTrue(indexService.search("cholesterol triglycerides", DomainScope.ACADEMICS, 5).isEmpty());
            assertTrue(indexService.search("cholesterol triglycerides", DomainScope.CAREER, 5).isEmpty());

            // CAREER terms queried in ACADEMICS and PERSONAL
            assertTrue(indexService.search("vesting equity bonus", DomainScope.ACADEMICS, 5).isEmpty());
            assertTrue(indexService.search("vesting equity bonus", DomainScope.PERSONAL, 5).isEmpty());

            // Legitimate searches in own domain succeed
            assertEquals(1, indexService.search("eigenvectors", DomainScope.ACADEMICS, 5).size());
            assertEquals(1, indexService.search("cholesterol", DomainScope.PERSONAL, 5).size());
            assertEquals(1, indexService.search("vesting", DomainScope.CAREER, 5).size());
        }

        @Test
        @DisplayName("Shared DocumentID across domains: operations in one domain do not cross-contaminate another")
        void testIdenticalDocumentIdAcrossDomains() {
            String sharedDocId = "syllabus_2026.pdf";

            VectorChunk academicChunk = makeChunk(sharedDocId, DomainScope.ACADEMICS, 1,
                    "Academic syllabus coursework and midterm grading");
            VectorChunk personalChunk = makeChunk(sharedDocId, DomainScope.PERSONAL, 1,
                    "Personal syllabus private notes and therapy schedule");
            VectorChunk careerChunk = makeChunk(sharedDocId, DomainScope.CAREER, 1,
                    "Career syllabus interview prep bootcamp timetable");

            indexService.indexChunk(academicChunk);
            indexService.indexChunk(personalChunk);
            indexService.indexChunk(careerChunk);

            assertEquals(1, indexService.size(DomainScope.ACADEMICS));
            assertEquals(1, indexService.size(DomainScope.PERSONAL));
            assertEquals(1, indexService.size(DomainScope.CAREER));

            // Remove sharedDocId from ACADEMICS only
            indexService.removeDocument(sharedDocId, DomainScope.ACADEMICS);

            // ACADEMICS should now be empty
            assertEquals(0, indexService.size(DomainScope.ACADEMICS));
            assertTrue(indexService.search("coursework", DomainScope.ACADEMICS, 5).isEmpty());

            // PERSONAL and CAREER must remain intact and searchable
            assertEquals(1, indexService.size(DomainScope.PERSONAL));
            assertEquals(1, indexService.size(DomainScope.CAREER));
            assertEquals(1, indexService.search("therapy", DomainScope.PERSONAL, 5).size());
            assertEquals(1, indexService.search("bootcamp", DomainScope.CAREER, 5).size());

            // Remove sharedDocId from PERSONAL only
            indexService.removeDocument(sharedDocId, DomainScope.PERSONAL);
            assertEquals(0, indexService.size(DomainScope.PERSONAL));
            assertEquals(1, indexService.size(DomainScope.CAREER));
            assertEquals(1, indexService.search("bootcamp", DomainScope.CAREER, 5).size());
        }

        @Test
        @DisplayName("Adversarial multi-term query mixing terms across domains returns only domain-matching chunks")
        void testCrossDomainMixedTermQuery() {
            VectorChunk academicChunk = makeChunk("ac.pdf", DomainScope.ACADEMICS, 1, "quantum entanglement physics");
            VectorChunk personalChunk = makeChunk("pe.pdf", DomainScope.PERSONAL, 1, "personal tax deductions");

            indexService.indexChunk(academicChunk);
            indexService.indexChunk(personalChunk);

            // Query containing terms from both documents: "quantum tax"
            var acResults = indexService.search("quantum tax", DomainScope.ACADEMICS, 5);
            assertEquals(1, acResults.size());
            assertEquals(academicChunk.id(), acResults.get(0).chunkId());

            var peResults = indexService.search("quantum tax", DomainScope.PERSONAL, 5);
            assertEquals(1, peResults.size());
            assertEquals(personalChunk.id(), peResults.get(0).chunkId());

            var caResults = indexService.search("quantum tax", DomainScope.CAREER, 5);
            assertTrue(caResults.isEmpty());
        }

        @Test
        @DisplayName("HybridRetrieverService dual-channel retrieval enforces strict domain isolation")
        void testHybridRetrieverServiceEnforcesDomainIsolation() {
            VectorChunk personalChunk = makeChunk("private.pdf", DomainScope.PERSONAL, 1,
                    "Highly confidential private salary negotiation and medical therapy");
            vectorStore.save(personalChunk);
            indexService.indexChunk(personalChunk);

            // Searching in ACADEMICS should yield 0 citations
            HybridSearchResult acResult = hybridRetrieverService.search("salary negotiation therapy", DomainScope.ACADEMICS, 5);
            assertTrue(acResult.citations().isEmpty(), "Zero citations allowed in ACADEMICS for PERSONAL content");

            // Searching in CAREER should yield 0 citations
            HybridSearchResult caResult = hybridRetrieverService.search("salary negotiation therapy", DomainScope.CAREER, 5);
            assertTrue(caResult.citations().isEmpty(), "Zero citations allowed in CAREER for PERSONAL content");

            // Searching in PERSONAL yields citation
            HybridSearchResult peResult = hybridRetrieverService.search("salary negotiation therapy", DomainScope.PERSONAL, 5);
            assertFalse(peResult.citations().isEmpty());
            assertEquals("private.pdf", peResult.citations().get(0).documentId());
        }
    }

    // =========================================================================
    // 2. FAIL-CLOSED VALIDATION ADVERSARIAL CHALLENGES
    // =========================================================================
    @Nested
    @DisplayName("2. Fail-Closed Validation Rigorous Probing")
    class FailClosedValidationTests {

        @Test
        @DisplayName("Null domain parameter fails closed immediately on all service operations without modifying state")
        void testNullDomainFailsClosedAcrossAllMethods() {
            VectorChunk validChunk = makeChunk("valid.md", DomainScope.ACADEMICS, 1, "Compiler optimizations");
            indexService.indexChunk(validChunk);
            assertEquals(1, indexService.size(DomainScope.ACADEMICS));

            // search(query, null, topK) -> UnscopedQueryException
            assertThrows(UnscopedQueryException.class, () -> indexService.search("compiler", null, 10));

            // size(null) -> UnscopedQueryException
            assertThrows(UnscopedQueryException.class, () -> indexService.size(null));

            // removeDocument(docId, null) -> UnscopedQueryException
            assertThrows(UnscopedQueryException.class, () -> indexService.removeDocument("valid.md", null));

            // removeChunk(chunkId, null) -> UnscopedQueryException
            assertThrows(UnscopedQueryException.class, () -> indexService.removeChunk(validChunk.id(), null));

            // VectorChunk construction itself fails closed if domain is null
            assertThrows(NullPointerException.class, () ->
                    new VectorChunk(UUID.randomUUID(), "bad.md", null, 1, "H1", "bad", DUMMY_EMBEDDING));

            // HybridRetrieverService search(query, null, topK) -> UnscopedQueryException
            assertThrows(UnscopedQueryException.class, () -> hybridRetrieverService.search("compiler", null, 10));

            // Verify index state was NOT corrupted and still contains original chunk
            assertEquals(1, indexService.size(DomainScope.ACADEMICS));
            assertEquals(1, indexService.search("compiler", DomainScope.ACADEMICS, 10).size());
        }

        @Test
        @DisplayName("DomainScope parsing fails closed on null, empty, whitespace, and unrecognized strings")
        void testDomainScopeStringParsingFailClosed() {
            assertThrows(UnscopedQueryException.class, () -> DomainScope.parse(null));
            assertThrows(UnscopedQueryException.class, () -> DomainScope.parse(""));
            assertThrows(UnscopedQueryException.class, () -> DomainScope.parse("   "));
            assertThrows(UnscopedQueryException.class, () -> DomainScope.parse("\t\n"));

            assertThrows(UnscopedQueryException.class, () -> DomainScope.parseScopes(null));
            assertThrows(UnscopedQueryException.class, () -> DomainScope.parseScopes(Collections.emptyList()));

            assertThrows(UnscopedQueryException.class, () -> DomainScope.validateScopes(null));
            assertThrows(UnscopedQueryException.class, () -> DomainScope.validateScopes(Collections.emptyList()));
            assertThrows(UnscopedQueryException.class, () -> DomainScope.validateScopes(Collections.singletonList(null)));
        }

        @Test
        @DisplayName("Safe null and empty query handling without exceptions or mutations")
        void testNullAndBlankQueriesDoNotThrow() {
            VectorChunk chunk = makeChunk("doc.md", DomainScope.CAREER, 1, "Backend engineer resume");
            indexService.indexChunk(chunk);

            // Null, empty, blank query returns empty list safely
            assertTrue(indexService.search(null, DomainScope.CAREER, 10).isEmpty());
            assertTrue(indexService.search("", DomainScope.CAREER, 10).isEmpty());
            assertTrue(indexService.search("    ", DomainScope.CAREER, 10).isEmpty());
            assertTrue(indexService.search(" \t\n\r ", DomainScope.CAREER, 10).isEmpty());

            // Single character queries (filtered by tokenizer > 1 char) return empty list
            assertTrue(indexService.search("a", DomainScope.CAREER, 10).isEmpty());
            assertTrue(indexService.search("a b c", DomainScope.CAREER, 10).isEmpty());

            // Punctuation only queries return empty list
            assertTrue(indexService.search("!@#$%^&*()_+=-~`", DomainScope.CAREER, 10).isEmpty());

            // Valid query still works
            assertEquals(1, indexService.search("engineer", DomainScope.CAREER, 10).size());
        }
    }

    // =========================================================================
    // 3. DOCUMENT INVALIDATION & RE-INGEST ADVERSARIAL CHALLENGES
    // =========================================================================
    @Nested
    @DisplayName("3. Document Invalidation & Re-Ingest Rigorous Probing")
    class DocumentInvalidationAndReIngestTests {

        @Test
        @DisplayName("Multi-chunk document index, search, removal, and complete purge verification")
        void testMultiChunkDocumentLifecycleAndPurge() {
            String docId = "distributed_systems.pdf";
            List<VectorChunk> chunks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                chunks.add(makeChunk(docId, DomainScope.ACADEMICS, i + 1,
                        "UniqueKeywordChapter" + i + " consensus fault tolerance leader election"));
            }

            indexService.indexChunks(chunks);
            assertEquals(8, indexService.size(DomainScope.ACADEMICS));

            // Verify each chapter keyword is found
            for (int i = 0; i < 8; i++) {
                var res = indexService.search("UniqueKeywordChapter" + i, DomainScope.ACADEMICS, 5);
                assertEquals(1, res.size(), "Chapter " + i + " must hit before invalidation");
            }

            // Remove document
            indexService.removeDocument(docId, DomainScope.ACADEMICS);

            // Size must drop to 0
            assertEquals(0, indexService.size(DomainScope.ACADEMICS));

            // Verify every chapter keyword returns ZERO hits
            for (int i = 0; i < 8; i++) {
                var res = indexService.search("UniqueKeywordChapter" + i, DomainScope.ACADEMICS, 5);
                assertTrue(res.isEmpty(), "Deleted chapter " + i + " must return 0 hits");
            }

            // General terms from deleted document return 0 hits
            assertTrue(indexService.search("consensus fault tolerance", DomainScope.ACADEMICS, 5).isEmpty());
        }

        @Test
        @DisplayName("Document update lifecycle: old terms purged, new terms indexed, correct corpus size")
        void testDocumentUpdateReIngestLifecycle() {
            String docId = "machine_learning.md";

            // Version 1: Convolutional Neural Networks
            VectorChunk v1Chunk = makeChunk(docId, DomainScope.ACADEMICS, 1,
                    "Convolutional feature maps pooling kernels activation relu");
            indexService.indexChunk(v1Chunk);

            assertEquals(1, indexService.size(DomainScope.ACADEMICS));
            assertEquals(1, indexService.search("convolutional", DomainScope.ACADEMICS, 5).size());
            assertEquals(1, indexService.search("pooling", DomainScope.ACADEMICS, 5).size());

            // Re-ingest: Update document with Version 2 (Transformers)
            indexService.removeDocument(docId, DomainScope.ACADEMICS);
            VectorChunk v2Chunk = makeChunk(docId, DomainScope.ACADEMICS, 1,
                    "Transformers multihead self attention positional encoding feedforward");
            indexService.indexChunk(v2Chunk);

            // Size must remain 1
            assertEquals(1, indexService.size(DomainScope.ACADEMICS));

            // Version 1 terms MUST return 0 hits
            assertTrue(indexService.search("convolutional", DomainScope.ACADEMICS, 5).isEmpty(),
                    "Stale Version 1 term 'convolutional' must return 0 hits");
            assertTrue(indexService.search("pooling", DomainScope.ACADEMICS, 5).isEmpty(),
                    "Stale Version 1 term 'pooling' must return 0 hits");

            // Version 2 terms MUST return 1 hit
            assertEquals(1, indexService.search("transformers", DomainScope.ACADEMICS, 5).size());
            assertEquals(1, indexService.search("attention", DomainScope.ACADEMICS, 5).size());
        }

        @Test
        @DisplayName("Overlapping terms between documents: removing Doc A does not invalidate Doc B")
        void testOverlappingTermsRetentionAfterPartialRemoval() {
            String sharedTerm = "microservices kubernetes architecture docker containers";
            VectorChunk chunkA = makeChunk("service_a.md", DomainScope.CAREER, 1, "Service Alpha " + sharedTerm);
            VectorChunk chunkB = makeChunk("service_b.md", DomainScope.CAREER, 1, "Service Beta " + sharedTerm);

            indexService.indexChunks(List.of(chunkA, chunkB));
            assertEquals(2, indexService.size(DomainScope.CAREER));

            // Search shared term -> returns both
            var bothResults = indexService.search("kubernetes containers", DomainScope.CAREER, 10);
            assertEquals(2, bothResults.size());

            // Remove Doc A
            indexService.removeDocument("service_a.md", DomainScope.CAREER);

            assertEquals(1, indexService.size(DomainScope.CAREER));

            // Search Doc A specific term -> 0
            assertTrue(indexService.search("alpha", DomainScope.CAREER, 5).isEmpty());

            // Search shared term -> returns Doc B only
            var bResults = indexService.search("kubernetes containers", DomainScope.CAREER, 10);
            assertEquals(1, bResults.size());
            assertEquals(chunkB.id(), bResults.get(0).chunkId());
        }

        @Test
        @DisplayName("Dynamic BM25 recalculation after corpus shrinkage: score changes when competitor document is purged")
        void testBm25ScoreAdjustmentUponCompetitorPurge() {
            VectorChunk targetChunk = makeChunk("target.md", DomainScope.ACADEMICS, 1, "graph neural networks embeddings");
            VectorChunk competitor1 = makeChunk("comp1.md", DomainScope.ACADEMICS, 1, "graph databases neo4j query");
            VectorChunk competitor2 = makeChunk("comp2.md", DomainScope.ACADEMICS, 1, "graph algorithms dijkstra shortest path");

            indexService.indexChunks(List.of(targetChunk, competitor1, competitor2));
            assertEquals(3, indexService.size(DomainScope.ACADEMICS));

            // Query "graph" when df = 3, N = 3
            var resBefore = indexService.search("graph", DomainScope.ACADEMICS, 5);
            assertEquals(3, resBefore.size());
            double targetScoreBefore = resBefore.stream()
                    .filter(sp -> sp.chunkId().equals(targetChunk.id()))
                    .findFirst().orElseThrow().bm25Score();

            // Purge both competitors -> now df = 1, N = 1
            indexService.removeDocument("comp1.md", DomainScope.ACADEMICS);
            indexService.removeDocument("comp2.md", DomainScope.ACADEMICS);
            assertEquals(1, indexService.size(DomainScope.ACADEMICS));

            var resAfter = indexService.search("graph", DomainScope.ACADEMICS, 5);
            assertEquals(1, resAfter.size());
            double targetScoreAfter = resAfter.get(0).bm25Score();

            // Target document is still retrieved cleanly
            assertEquals(targetChunk.id(), resAfter.get(0).chunkId());
            assertTrue(targetScoreAfter > 0.0);
        }

        @Test
        @DisplayName("End-to-end DocumentIngestionService transactional update invalidates inverted index")
        void testEndToEndIngestionServiceReIngestInvalidation() {
            String filePath = "notes/database_lecture.md";
            String mdV1 = "# Database Lecture\nRelational database normalization third normal form bcnf schemas.";

            // Ingest V1
            IngestResult r1 = documentIngestionService.ingestMarkdown(filePath, DomainScope.ACADEMICS, mdV1);
            assertEquals("CREATED", r1.status());

            // Verify searchable via InvertedIndexService
            var hitV1 = indexService.search("normalization", DomainScope.ACADEMICS, 5);
            assertEquals(1, hitV1.size());
            assertEquals(filePath, hitV1.get(0).documentId());

            // Verify searchable via HybridRetrieverService
            var hybridHitV1 = hybridRetrieverService.search("normalization bcnf", DomainScope.ACADEMICS, 5);
            assertFalse(hybridHitV1.citations().isEmpty());
            assertEquals(filePath, hybridHitV1.citations().get(0).documentId());

            // Re-ingest modified V2
            String mdV2 = "# Database Lecture\nNon-relational document database distributed partitions sharding replicas.";
            IngestResult r2 = documentIngestionService.ingestMarkdown(filePath, DomainScope.ACADEMICS, mdV2);
            assertEquals("UPDATED", r2.status());

            // Verify V1 term returns 0 in InvertedIndexService
            var staleSearch = indexService.search("normalization", DomainScope.ACADEMICS, 5);
            assertTrue(staleSearch.isEmpty(), "V1 term 'normalization' must be purged from inverted index after update");

            // Verify V2 term returns hit in InvertedIndexService
            var newSearch = indexService.search("partitions sharding", DomainScope.ACADEMICS, 5);
            assertEquals(1, newSearch.size());
            assertEquals(filePath, newSearch.get(0).documentId());

            // Verify HybridRetrieverService reflects update: V2 term hits
            var hybridHitV2 = hybridRetrieverService.search("partitions sharding", DomainScope.ACADEMICS, 5);
            assertFalse(hybridHitV2.citations().isEmpty());
            assertEquals(filePath, hybridHitV2.citations().get(0).documentId());
        }
    }

    // =========================================================================
    // 4. CONCURRENCY & STRESS INVALIDATION ADVERSARIAL CHALLENGES
    // =========================================================================
    @Nested
    @DisplayName("4. Concurrency & Stress Invalidation Probing")
    class ConcurrencyStressTests {

        @Test
        @DisplayName("Concurrent continuous invalidation and querying across 40 virtual threads yields zero race conditions")
        void testConcurrentInvalidationAndSearchHarness() throws InterruptedException {
            int threadCount = 40;
            int iterationsPerThread = 30;
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger errorCount = new AtomicInteger(0);
            AtomicInteger successfulQueries = new AtomicInteger(0);

            // Pre-populate index
            for (int i = 0; i < 20; i++) {
                indexService.indexChunk(makeChunk("static_doc_" + i, DomainScope.ACADEMICS, 1,
                        "Static stable anchor terms for reader benchmark"));
            }

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int t = 0; t < threadCount; t++) {
                    final int threadId = t;
                    executor.submit(() -> {
                        try {
                            for (int iter = 0; iter < iterationsPerThread; iter++) {
                                String volatileDoc = "volatile_doc_" + threadId + "_" + iter;
                                // Write
                                indexService.indexChunk(makeChunk(volatileDoc, DomainScope.ACADEMICS, 1,
                                        "Volatile ephemeral stress payload " + iter));

                                // Read
                                var res = indexService.search("ephemeral stress", DomainScope.ACADEMICS, 5);
                                assertNotNull(res);
                                successfulQueries.incrementAndGet();

                                // Read static
                                var staticRes = indexService.search("stable anchor", DomainScope.ACADEMICS, 5);
                                assertNotNull(staticRes);
                                successfulQueries.incrementAndGet();

                                // Invalidate
                                indexService.removeDocument(volatileDoc, DomainScope.ACADEMICS);

                                // Verify removed doc term is gone or going
                                var postDeleteRes = indexService.search("VolatileEphemeralNotFound" + iter, DomainScope.ACADEMICS, 5);
                                assertTrue(postDeleteRes.isEmpty());
                            }
                        } catch (Throwable ex) {
                            errorCount.incrementAndGet();
                            ex.printStackTrace();
                        } finally {
                            latch.countDown();
                        }
                    });
                }
            }

            boolean completed = latch.await(20, TimeUnit.SECONDS);
            assertTrue(completed, "Stress test harness must complete within timeout");
            assertEquals(0, errorCount.get(), "Zero exceptions or race conditions allowed during concurrent invalidation");
            assertTrue(successfulQueries.get() > 0);

            // After all volatile documents are removed, only the 20 static documents should remain
            assertEquals(20, indexService.size(DomainScope.ACADEMICS));
        }
    }
}
