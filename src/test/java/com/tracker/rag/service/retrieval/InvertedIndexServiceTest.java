package com.tracker.rag.service.retrieval;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.domain.UnscopedQueryException;
import com.tracker.rag.dto.Citation;
import com.tracker.rag.dto.HybridSearchResult;
import com.tracker.rag.repository.vector.EmbeddedVectorStore;
import com.tracker.rag.repository.vector.VectorChunk;
import com.tracker.rag.service.HybridRetrieverService;
import com.tracker.rag.service.OllamaClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InvertedIndexService BM25, Domain Isolation, Invalidation & Latency Tests")
public class InvertedIndexServiceTest {

    private InvertedIndexServiceImpl indexService;
    private static final float[] DUMMY_EMBEDDING = new float[768];

    @BeforeEach
    void setUp() {
        indexService = new InvertedIndexServiceImpl(null);
    }

    private VectorChunk createChunk(String docId, DomainScope domain, int page, String heading, String content) {
        return new VectorChunk(UUID.randomUUID(), docId, domain, page, heading, content, DUMMY_EMBEDDING);
    }

    private VectorChunk createChunk(String docId, DomainScope domain, int page, String content) {
        return createChunk(docId, domain, page, "H1 > Section", content);
    }

    @Nested
    @DisplayName("1. BM25 Ranking Accuracy & Mechanics")
    class Bm25RankingAccuracyTests {

        @Test
        @DisplayName("TF Saturation: High TF scores higher but exhibits sub-linear diminishing returns (k1=1.2)")
        void testTfSaturation() {
            VectorChunk chunkLow = createChunk("doc1.md", DomainScope.ACADEMICS, 1, "Compiler optimization algorithms and dead code elimination.");
            VectorChunk chunkHigh = createChunk("doc2.md", DomainScope.ACADEMICS, 1, "Compiler compiler compiler optimization algorithms and dead code elimination.");

            indexService.indexChunk(chunkLow);
            indexService.indexChunk(chunkHigh);

            List<InvertedIndexService.ScoredPosting> results = indexService.search("compiler", DomainScope.ACADEMICS, 10);
            assertEquals(2, results.size());
            assertEquals(chunkHigh.id(), results.get(0).chunkId(), "Higher TF chunk must rank first");
            assertTrue(results.get(0).bm25Score() > results.get(1).bm25Score(), "Higher TF chunk must have higher BM25 score");
            // Under k1 = 1.2, 3 occurrences vs 1 occurrence must be strictly sub-linear (< 3x)
            assertTrue(results.get(0).bm25Score() < 3.0 * results.get(1).bm25Score(),
                    "Score scaling must be sub-linear due to k1 term saturation");
        }

        @Test
        @DisplayName("Document Length Normalization: Shorter document with identical TF scores higher (b=0.75)")
        void testDocumentLengthNormalization() {
            String shortContent = "Distributed consensus algorithm";
            String longContent = "Distributed consensus algorithm with extensive historical context and multiple long paragraphs " +
                    "explaining Paxos, Raft, Byzantine fault tolerance, network partitions, split-brain scenarios, " +
                    "vector clocks, consistent hashing, gossip protocols, and leader election mechanisms across clusters.";

            VectorChunk shortChunk = createChunk("short.md", DomainScope.ACADEMICS, 1, shortContent);
            VectorChunk longChunk = createChunk("long.md", DomainScope.ACADEMICS, 1, longContent);

            indexService.indexChunk(shortChunk);
            indexService.indexChunk(longChunk);

            List<InvertedIndexService.ScoredPosting> results = indexService.search("consensus", DomainScope.ACADEMICS, 10);
            assertEquals(2, results.size());
            assertEquals(shortChunk.id(), results.get(0).chunkId(), "Shorter chunk with identical TF must rank first");
            assertTrue(results.get(0).bm25Score() > results.get(1).bm25Score(),
                    "Shorter chunk must have higher score due to length penalization");
        }

        @Test
        @DisplayName("Robertson IDF Weighting: Rare terms contribute significantly more score than common terms")
        void testRobertsonIdfWeighting() {
            // Corpus where 'algorithm' appears in 5 docs, but 'quicksort' appears in only 1 doc
            for (int i = 1; i <= 4; i++) {
                indexService.indexChunk(createChunk("doc" + i + ".md", DomainScope.ACADEMICS, 1,
                        "General computer science algorithm design"));
            }
            VectorChunk specificChunk = createChunk("quicksort.md", DomainScope.ACADEMICS, 1,
                    "General computer science quicksort design");
            indexService.indexChunk(specificChunk);

            // In query "quicksort algorithm", quicksort (rare) should provide much higher weight than algorithm (common)
            List<InvertedIndexService.ScoredPosting> results = indexService.search("quicksort algorithm", DomainScope.ACADEMICS, 10);
            assertFalse(results.isEmpty());
            assertEquals(specificChunk.id(), results.get(0).chunkId());
            assertTrue(results.get(0).bm25Score() > 0.0);
        }

        @Test
        @DisplayName("Exact multi-token match ranks higher than partial single-token match")
        void testMultiTokenMatching() {
            VectorChunk exactMatch = createChunk("exact.md", DomainScope.ACADEMICS, 1,
                    "Relational database indexing with b-tree structures");
            VectorChunk partialMatch = createChunk("partial.md", DomainScope.ACADEMICS, 1,
                    "Relational database schemas and normalization");

            indexService.indexChunk(exactMatch);
            indexService.indexChunk(partialMatch);

            List<InvertedIndexService.ScoredPosting> results = indexService.search("database indexing b-tree", DomainScope.ACADEMICS, 5);
            assertEquals(2, results.size());
            assertEquals(exactMatch.id(), results.get(0).chunkId());
            assertTrue(results.get(0).bm25Score() > results.get(1).bm25Score());
        }
    }

    @Nested
    @DisplayName("2. Tokenization & Delimiter Handling")
    class TokenizationTests {

        @Test
        @DisplayName("Preserves alphanumeric terms > 1 character including course codes and acronyms")
        void testAlphanumericTokenPreservation() {
            VectorChunk chunk = createChunk("course.md", DomainScope.ACADEMICS, 1,
                    "CS 3500 Software Practice covers SHA-256 hashing and 8b model weights.");
            indexService.indexChunk(chunk);

            // Check "cs"
            var csResults = indexService.search("cs", DomainScope.ACADEMICS, 5);
            assertEquals(1, csResults.size(), "Term 'cs' (length 2 > 1) must be preserved");

            // Check "3500"
            var numResults = indexService.search("3500", DomainScope.ACADEMICS, 5);
            assertEquals(1, numResults.size(), "Numeric term '3500' must be preserved");

            // Check "sha-256" -> tokens "sha", "256"
            var shaResults = indexService.search("sha-256", DomainScope.ACADEMICS, 5);
            assertEquals(1, shaResults.size(), "Hyphenated 'sha-256' must match via tokens 'sha' and '256'");

            // Check "8b"
            var modelResults = indexService.search("8b", DomainScope.ACADEMICS, 5);
            assertEquals(1, modelResults.size(), "Alphanumeric '8b' must be preserved");
        }

        @Test
        @DisplayName("Case-insensitivity: Mixed-case queries match lowercase or uppercase indexed text")
        void testCaseInsensitivity() {
            VectorChunk chunk = createChunk("notes.md", DomainScope.ACADEMICS, 1,
                    "DYNAMIC PROGRAMMING and MEMOIZATION techniques.");
            indexService.indexChunk(chunk);

            var lowerResults = indexService.search("dynamic programming", DomainScope.ACADEMICS, 5);
            assertEquals(1, lowerResults.size());

            var upperResults = indexService.search("MEMOIZATION", DomainScope.ACADEMICS, 5);
            assertEquals(1, upperResults.size());
        }
    }

    @Nested
    @DisplayName("3. Domain Isolation & Fail-Closed Scoping")
    class DomainIsolationAndSecurityTests {

        @Test
        @DisplayName("Strict Domain Isolation: Queries to ACADEMICS return zero records from PERSONAL or CAREER")
        void testStrictDomainIsolation() {
            VectorChunk personalChunk = createChunk("finances.pdf", DomainScope.PERSONAL, 1,
                    "Confidential personal tax return and investment accounts.");
            VectorChunk academicChunk = createChunk("cs2100.pdf", DomainScope.ACADEMICS, 1,
                    "Computer architecture instruction set architecture.");
            VectorChunk careerChunk = createChunk("resume.pdf", DomainScope.CAREER, 1,
                    "Software engineering intern resume and portfolio.");

            indexService.indexChunk(personalChunk);
            indexService.indexChunk(academicChunk);
            indexService.indexChunk(careerChunk);

            assertEquals(1, indexService.size(DomainScope.PERSONAL));
            assertEquals(1, indexService.size(DomainScope.ACADEMICS));
            assertEquals(1, indexService.size(DomainScope.CAREER));

            // Query personal term in ACADEMICS -> must be empty
            List<InvertedIndexService.ScoredPosting> academicSearch =
                    indexService.search("tax return investment", DomainScope.ACADEMICS, 10);
            assertTrue(academicSearch.isEmpty(), "ACADEMICS search must return zero results from PERSONAL");

            // Query personal term in PERSONAL -> returns personalChunk
            List<InvertedIndexService.ScoredPosting> personalSearch =
                    indexService.search("tax return investment", DomainScope.PERSONAL, 10);
            assertEquals(1, personalSearch.size());
            assertEquals(personalChunk.id(), personalSearch.get(0).chunkId());

            // Query career term in ACADEMICS -> must be empty
            List<InvertedIndexService.ScoredPosting> careerInAcademic =
                    indexService.search("resume intern portfolio", DomainScope.ACADEMICS, 10);
            assertTrue(careerInAcademic.isEmpty());
        }

        @Test
        @DisplayName("Fail-Closed Validation: Null domain throws UnscopedQueryException immediately")
        void testFailClosedOnNullDomain() {
            assertThrows(UnscopedQueryException.class, () -> indexService.search("test", null, 10));
            assertThrows(UnscopedQueryException.class, () -> indexService.size(null));
            assertThrows(UnscopedQueryException.class, () -> indexService.removeDocument("doc.md", null));
            assertThrows(UnscopedQueryException.class, () -> indexService.removeChunk(UUID.randomUUID(), null));
        }
    }

    @Nested
    @DisplayName("4. Document Lifecycle & Invalidation")
    class DocumentLifecycleTests {

        @Test
        @DisplayName("removeDocument purges all chunks and resets corpus size accurately")
        void testRemoveDocument() {
            String docA = "syllabus.pdf";
            String docB = "lecture.pdf";

            VectorChunk c1 = createChunk(docA, DomainScope.ACADEMICS, 1, "Grading criteria and final exam schedule");
            VectorChunk c2 = createChunk(docA, DomainScope.ACADEMICS, 2, "Grading rubric and office hours");
            VectorChunk c3 = createChunk(docB, DomainScope.ACADEMICS, 1, "Graph traversal breadth-first search");

            indexService.indexChunks(List.of(c1, c2, c3));
            assertEquals(3, indexService.size(DomainScope.ACADEMICS));

            // Remove docA
            indexService.removeDocument(docA, DomainScope.ACADEMICS);

            assertEquals(1, indexService.size(DomainScope.ACADEMICS));
            assertTrue(indexService.search("grading criteria", DomainScope.ACADEMICS, 5).isEmpty());
            assertEquals(1, indexService.search("graph traversal", DomainScope.ACADEMICS, 5).size());
        }

        @Test
        @DisplayName("Re-ingestion update atomically replaces old document terms with new terms")
        void testAtomicReIngestionUpdate() {
            String docId = "notes/quantum.md";
            VectorChunk v1 = createChunk(docId, DomainScope.ACADEMICS, 1, "Quantum entanglement and qubits principles");
            indexService.indexChunk(v1);

            assertEquals(1, indexService.search("entanglement", DomainScope.ACADEMICS, 5).size());

            // Re-ingest: remove old version and index new version
            indexService.removeDocument(docId, DomainScope.ACADEMICS);
            VectorChunk v2 = createChunk(docId, DomainScope.ACADEMICS, 1, "Classical thermodynamics and entropy principles");
            indexService.indexChunk(v2);

            // Old term returns 0
            assertEquals(0, indexService.search("entanglement", DomainScope.ACADEMICS, 5).size());
            // New term returns 1
            assertEquals(1, indexService.search("thermodynamics", DomainScope.ACADEMICS, 5).size());
            assertEquals(1, indexService.size(DomainScope.ACADEMICS));
        }

        @Test
        @DisplayName("removeChunk purges individual chunk from inverted index")
        void testRemoveIndividualChunk() {
            VectorChunk c1 = createChunk("doc.md", DomainScope.CAREER, 1, "Senior Java Backend Engineer");
            VectorChunk c2 = createChunk("doc.md", DomainScope.CAREER, 2, "Kubernetes and Docker microservices");

            indexService.indexChunks(List.of(c1, c2));
            assertEquals(2, indexService.size(DomainScope.CAREER));

            indexService.removeChunk(c1.id(), DomainScope.CAREER);
            assertEquals(1, indexService.size(DomainScope.CAREER));
            assertTrue(indexService.search("backend engineer", DomainScope.CAREER, 5).isEmpty());
            assertEquals(1, indexService.search("kubernetes", DomainScope.CAREER, 5).size());
        }

        @Test
        @DisplayName("clear() purges all domains completely")
        void testClearAllDomains() {
            indexService.indexChunk(createChunk("d1.md", DomainScope.ACADEMICS, 1, "Academic note"));
            indexService.indexChunk(createChunk("d2.md", DomainScope.PERSONAL, 1, "Personal note"));
            indexService.indexChunk(createChunk("d3.md", DomainScope.CAREER, 1, "Career note"));

            indexService.clear();

            assertEquals(0, indexService.size(DomainScope.ACADEMICS));
            assertEquals(0, indexService.size(DomainScope.PERSONAL));
            assertEquals(0, indexService.size(DomainScope.CAREER));
        }
    }

    @Nested
    @DisplayName("5. Edge Cases & Boundary Handling")
    class EdgeCasesTests {

        @Test
        @DisplayName("Empty or whitespace queries return empty results without error")
        void testEmptyAndBlankQueries() {
            indexService.indexChunk(createChunk("doc.md", DomainScope.ACADEMICS, 1, "Valid content here"));

            assertTrue(indexService.search("", DomainScope.ACADEMICS, 10).isEmpty());
            assertTrue(indexService.search("    ", DomainScope.ACADEMICS, 10).isEmpty());
            assertTrue(indexService.search(null, DomainScope.ACADEMICS, 10).isEmpty());
        }

        @Test
        @DisplayName("Empty corpus returns empty results immediately without division-by-zero")
        void testEmptyCorpusSearch() {
            assertEquals(0, indexService.size(DomainScope.ACADEMICS));
            assertTrue(indexService.search("any query", DomainScope.ACADEMICS, 10).isEmpty());
        }

        @Test
        @DisplayName("topK <= 0 returns empty results safely")
        void testInvalidTopK() {
            indexService.indexChunk(createChunk("doc.md", DomainScope.ACADEMICS, 1, "Valid content"));
            assertTrue(indexService.search("valid", DomainScope.ACADEMICS, 0).isEmpty());
            assertTrue(indexService.search("valid", DomainScope.ACADEMICS, -5).isEmpty());
        }

        @Test
        @DisplayName("Non-existent terms return empty results safely")
        void testNonExistentTerms() {
            indexService.indexChunk(createChunk("doc.md", DomainScope.ACADEMICS, 1, "Valid content"));
            assertTrue(indexService.search("nonexistenttermxyz", DomainScope.ACADEMICS, 10).isEmpty());
        }

        @Test
        @DisplayName("Re-indexing same chunkId replaces existing chunk without duplicating corpus counts")
        void testReindexingSameChunkId() {
            UUID id = UUID.randomUUID();
            VectorChunk chunk1 = new VectorChunk(id, "doc.md", DomainScope.ACADEMICS, 1, "H1", "First version content", DUMMY_EMBEDDING);
            VectorChunk chunk2 = new VectorChunk(id, "doc.md", DomainScope.ACADEMICS, 1, "H1", "Second replacement content", DUMMY_EMBEDDING);

            indexService.indexChunk(chunk1);
            assertEquals(1, indexService.size(DomainScope.ACADEMICS));

            indexService.indexChunk(chunk2);
            assertEquals(1, indexService.size(DomainScope.ACADEMICS));

            assertEquals(0, indexService.search("first", DomainScope.ACADEMICS, 5).size());
            assertEquals(1, indexService.search("replacement", DomainScope.ACADEMICS, 5).size());
        }
    }

    @Nested
    @DisplayName("6. Startup Hydration from VectorStore")
    class HydrationTests {

        @Test
        @DisplayName("InvertedIndexService hydrates pre-existing chunks from VectorStore in @PostConstruct")
        void testStartupHydration() {
            EmbeddedVectorStore vectorStore = new EmbeddedVectorStore();
            VectorChunk c1 = createChunk("existing1.pdf", DomainScope.ACADEMICS, 1, "Hydrated academic notes from storage");
            VectorChunk c2 = createChunk("existing2.pdf", DomainScope.PERSONAL, 1, "Hydrated personal notes from storage");
            vectorStore.saveAll(List.of(c1, c2));

            InvertedIndexServiceImpl hydratedService = new InvertedIndexServiceImpl(vectorStore);
            hydratedService.hydrateOnStartup();

            assertEquals(1, hydratedService.size(DomainScope.ACADEMICS));
            assertEquals(1, hydratedService.size(DomainScope.PERSONAL));

            var results = hydratedService.search("hydrated academic", DomainScope.ACADEMICS, 5);
            assertEquals(1, results.size());
            assertEquals(c1.id(), results.get(0).chunkId());
        }
    }

    @Nested
    @DisplayName("7. Latency Benchmark (<15ms across 1,000+ chunks)")
    class LatencyBenchmarkTests {

        @Test
        @DisplayName("Sparse BM25 search executes well below 15ms across 1,000 indexed chunks")
        void testSub15MsLatencyOver1000Chunks() {
            int chunkCount = 1000;
            String[] vocab = {
                    "compiler", "optimization", "register", "allocation", "virtual", "memory",
                    "paging", "cache", "lru", "hardware", "concurrency", "mutex",
                    "thread", "distributed", "consensus", "paxos", "raft", "database",
                    "b-tree", "transaction", "acid", "serialization", "network", "socket"
            };
            Random rand = new Random(42);

            List<VectorChunk> batch = new ArrayList<>(chunkCount);
            for (int i = 0; i < chunkCount; i++) {
                StringBuilder content = new StringBuilder();
                int wordCount = 40 + rand.nextInt(30);
                for (int w = 0; w < wordCount; w++) {
                    content.append(vocab[rand.nextInt(vocab.length)]).append(" ");
                }
                batch.add(createChunk("benchmark_doc_" + (i / 10) + ".pdf", DomainScope.ACADEMICS, (i % 10) + 1, content.toString()));
            }

            indexService.indexChunks(batch);
            assertEquals(1000, indexService.size(DomainScope.ACADEMICS));

            // Warm up
            for (int i = 0; i < 50; i++) {
                indexService.search("virtual memory paging", DomainScope.ACADEMICS, 10);
            }

            // Benchmark 200 diverse queries
            List<Long> latenciesNanos = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                String queryTerm1 = vocab[rand.nextInt(vocab.length)];
                String queryTerm2 = vocab[rand.nextInt(vocab.length)];
                long start = System.nanoTime();
                var results = indexService.search(queryTerm1 + " " + queryTerm2, DomainScope.ACADEMICS, 10);
                long elapsed = System.nanoTime() - start;
                assertNotNull(results);
                latenciesNanos.add(elapsed);
            }

            Collections.sort(latenciesNanos);
            double p50Ms = latenciesNanos.get((int) (latenciesNanos.size() * 0.50)) / 1_000_000.0;
            double p95Ms = latenciesNanos.get((int) (latenciesNanos.size() * 0.95)) / 1_000_000.0;
            double p99Ms = latenciesNanos.get((int) (latenciesNanos.size() * 0.99)) / 1_000_000.0;

            System.out.printf("[InvertedIndex BM25 Latency Benchmark] 1,000 chunks -> P50: %.4f ms, P95: %.4f ms, P99: %.4f ms%n",
                    p50Ms, p95Ms, p99Ms);

            assertTrue(p95Ms < 15.0, "P95 latency must be under 15ms. Was: " + p95Ms + " ms");
            assertTrue(p99Ms < 25.0, "P99 latency must be under 25ms. Was: " + p99Ms + " ms");
        }
    }

    @Nested
    @DisplayName("8. HybridRetrieverService Dual-Channel Integration")
    class HybridRetrieverIntegrationTests {

        @Test
        @DisplayName("HybridRetrieverService queries sparse inverted index independently and fuses via RRF (k=60)")
        void testDualChannelRetrievalAndFusion() {
            EmbeddedVectorStore vectorStore = new EmbeddedVectorStore();
            OllamaClientService stubOllama = new OllamaClientService(new com.fasterxml.jackson.databind.ObjectMapper());

            // Chunk A: High semantic similarity stub, no exact keyword
            VectorChunk chunkA = createChunk("dense_doc.md", DomainScope.ACADEMICS, 1, "Semantic theory of computation");
            // Chunk B: Has exact keyword "CS 3500", sparse match
            VectorChunk chunkB = createChunk("sparse_doc.md", DomainScope.ACADEMICS, 1, "Course code CS 3500 Software Practice");

            vectorStore.saveAll(List.of(chunkA, chunkB));
            indexService.indexChunks(List.of(chunkA, chunkB));

            HybridRetrieverService retriever = new HybridRetrieverService(vectorStore, stubOllama, indexService);

            HybridSearchResult result = retriever.search("CS 3500", DomainScope.ACADEMICS, 5);
            assertNotNull(result);
            assertFalse(result.citations().isEmpty());

            Citation topCitation = result.citations().get(0);
            assertNotNull(topCitation.documentId());
            assertTrue(topCitation.score() > 0.0);
        }
    }
}
