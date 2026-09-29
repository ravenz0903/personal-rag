package com.tracker.rag.e2e.tier1;

import com.tracker.rag.e2e.contract.*;
import com.tracker.rag.e2e.stub.DeterministicHybridRetrieverStub;
import com.tracker.rag.e2e.stub.LoopbackEnforcingClientStub;
import com.tracker.rag.e2e.stub.PureJavaVectorStoreStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Tier 1: Hybrid Retrieval, Citations & Local Inference Tests (F24-F30)")
public class Tier1RetrievalAndInferenceTest {

    private PureJavaVectorStoreStub vectorStore;
    private List<VectorChunk> chunkList;
    private DeterministicHybridRetrieverStub retriever;

    @BeforeEach
    void setUp() {
        vectorStore = new PureJavaVectorStoreStub();
        chunkList = new ArrayList<>();
    }

    private void addTestChunk(String docId, DomainScope domain, int page, String heading, String text) {
        float[] emb = LoopbackEnforcingClientStub.generateDeterministicEmbedding(text);
        VectorChunk chunk = new VectorChunk(UUID.randomUUID(), docId, domain, page, heading, text, emb);
        chunkList.add(chunk);
        vectorStore.save(chunk);
    }

    @Nested
    @DisplayName("F24 & F25: Dense Vector Search & Sparse Keyword Search Tests")
    class SearchEnginesTests {

        @Test
        @DisplayName("TC-F24-01: 768-dimensional dense similarity search retrieves closest vector")
        void testDenseVectorSearch() {
            addTestChunk("doc1.pdf", DomainScope.ACADEMICS, 1, null, "Quantum mechanics wave functions");
            addTestChunk("doc2.pdf", DomainScope.ACADEMICS, 2, null, "Organic chemistry synthesis pathways");

            float[] queryVec = LoopbackEnforcingClientStub.generateDeterministicEmbedding("Quantum mechanics");
            List<ScoredChunk> results = vectorStore.similaritySearch(queryVec, DomainScope.ACADEMICS, 2);

            assertEquals(2, results.size());
            assertEquals("Quantum mechanics wave functions", results.get(0).chunk().content());
            assertTrue(results.get(0).score() > results.get(1).score());
        }

        @Test
        @DisplayName("TC-F24-02: Dense search strictly respects domain filtering")
        void testDenseSearchDomainFilter() {
            addTestChunk("study.pdf", DomainScope.ACADEMICS, 1, null, "Algorithms and Big-O notation");
            addTestChunk("journal.md", DomainScope.PERSONAL, 0, null, "Algorithms for personal time management");

            float[] queryVec = LoopbackEnforcingClientStub.generateDeterministicEmbedding("Algorithms");
            List<ScoredChunk> results = vectorStore.similaritySearch(queryVec, DomainScope.ACADEMICS, 5);

            assertEquals(1, results.size());
            assertEquals(DomainScope.ACADEMICS, results.get(0).chunk().domain());
        }

        @Test
        @DisplayName("TC-F25-01: Sparse keyword search finds exact keywords even when phrasing differs")
        void testSparseKeywordSearch() {
            addTestChunk("syllabus.pdf", DomainScope.ACADEMICS, 1, null, "The course code is CS 3500 Object Oriented Design");
            addTestChunk("other.pdf", DomainScope.ACADEMICS, 1, null, "General programming fundamentals");

            retriever = new DeterministicHybridRetrieverStub(vectorStore, chunkList);
            HybridSearchResult result = retriever.search("CS 3500", DomainScope.ACADEMICS, 1);

            assertEquals(1, result.citations().size());
            assertTrue(result.citations().get(0).snippet().contains("CS 3500"));
        }

        @Test
        @DisplayName("TC-F25-02: Search with zero matches returns empty citations list")
        void testZeroMatchesReturnsEmpty() {
            retriever = new DeterministicHybridRetrieverStub(vectorStore, chunkList);
            HybridSearchResult result = retriever.search("NonexistentTerm123XYZ", DomainScope.ACADEMICS, 5);

            assertTrue(result.citations().isEmpty());
        }

        @Test
        @DisplayName("TC-F25-03: Search fails closed on null domain scope")
        void testSearchNullDomainThrows() {
            retriever = new DeterministicHybridRetrieverStub(vectorStore, chunkList);
            assertThrows(UnscopedQueryException.class, () ->
                    retriever.search("Query", null, 5));
        }
    }

    @Nested
    @DisplayName("F26: Reciprocal Rank Fusion (RRF k=60) Tests")
    class RrfTests {

        @Test
        @DisplayName("TC-F26-01: RRF score calculation follows formula 1 / (60 + rank)")
        void testRrfFormula() {
            addTestChunk("top.pdf", DomainScope.ACADEMICS, 1, null, "Target document with exact keywords and semantic meaning");
            retriever = new DeterministicHybridRetrieverStub(vectorStore, chunkList);

            HybridSearchResult result = retriever.search("Target document exact keywords", DomainScope.ACADEMICS, 5);
            assertEquals(1, result.citations().size());

            Citation citation = result.citations().get(0);
            // Rank 1 in both dense and sparse: score = 1/(60+1) + 1/(60+1) = 2/61 ≈ 0.032786
            double expectedScore = (1.0 / 61.0) + (1.0 / 61.0);
            assertEquals(expectedScore, citation.rrfScore(), 1e-4);
        }

        @Test
        @DisplayName("TC-F26-02: RRF ranks chunk appearing in both lists above chunk appearing in only one")
        void testRrfRanksSharedHigher() {
            addTestChunk("both.pdf", DomainScope.CAREER, 1, null, "Senior Software Engineer backend Go Java AWS");
            addTestChunk("sparse_only.pdf", DomainScope.CAREER, 1, null, "Unrelated text mentioning backend Go Java AWS");

            retriever = new DeterministicHybridRetrieverStub(vectorStore, chunkList);
            HybridSearchResult result = retriever.search("Software Engineer backend", DomainScope.CAREER, 5);

            assertTrue(result.citations().size() >= 1);
            assertEquals("both.pdf", result.citations().get(0).sourceFile());
        }

        @Test
        @DisplayName("TC-F26-03: RRF results are sorted descending by rrfScore")
        void testRrfSortedDescending() {
            addTestChunk("docA.pdf", DomainScope.ACADEMICS, 1, null, "Linear Algebra matrices and vectors");
            addTestChunk("docB.pdf", DomainScope.ACADEMICS, 2, null, "Matrices decomposition Eigenvalues");
            addTestChunk("docC.pdf", DomainScope.ACADEMICS, 3, null, "Unrelated botanical history");

            retriever = new DeterministicHybridRetrieverStub(vectorStore, chunkList);
            HybridSearchResult result = retriever.search("Matrices", DomainScope.ACADEMICS, 3);

            for (int i = 0; i < result.citations().size() - 1; i++) {
                assertTrue(result.citations().get(i).rrfScore() >= result.citations().get(i + 1).rrfScore());
            }
        }

        @Test
        @DisplayName("TC-F26-04: RRF topK limits the number of citations returned")
        void testRrfTopKLimit() {
            for (int i = 1; i <= 10; i++) {
                addTestChunk("doc" + i + ".pdf", DomainScope.ACADEMICS, i, null, "Common study topic item " + i);
            }
            retriever = new DeterministicHybridRetrieverStub(vectorStore, chunkList);

            HybridSearchResult result = retriever.search("study topic", DomainScope.ACADEMICS, 3);
            assertEquals(3, result.citations().size());
        }

        @Test
        @DisplayName("TC-F26-05: Empty query returns empty citations without error")
        void testEmptyQueryReturnsEmpty() {
            addTestChunk("doc.pdf", DomainScope.ACADEMICS, 1, null, "Content");
            retriever = new DeterministicHybridRetrieverStub(vectorStore, chunkList);

            HybridSearchResult result = retriever.search("", DomainScope.ACADEMICS, 5);
            assertTrue(result.citations().isEmpty());
        }
    }

    @Nested
    @DisplayName("F27: Page-Accurate Citations Tests")
    class CitationsTests {

        @Test
        @DisplayName("TC-F27-01: PDF citation produces [file, Page X] source tag")
        void testPdfCitationSourceTag() {
            Citation citation = new Citation("syllabus.pdf", 4, null, "Final Exam is on Dec 15", 0.03);
            assertEquals("[syllabus.pdf, Page 4]", citation.toSourceTag());
        }

        @Test
        @DisplayName("TC-F27-02: Markdown citation produces [file, Section: Path] source tag")
        void testMarkdownCitationSourceTag() {
            Citation citation = new Citation("notes.md", 0, "CS101 > Midterm > Policies", "Snippet", 0.02);
            assertEquals("[notes.md, Section: CS101 > Midterm > Policies]", citation.toSourceTag());
        }

        @Test
        @DisplayName("TC-F27-03: Fallback citation produces [file] tag when page and heading are absent")
        void testFallbackCitationSourceTag() {
            Citation citation = new Citation("plain.txt", 0, null, "Plain text", 0.01);
            assertEquals("[plain.txt]", citation.toSourceTag());
        }

        @Test
        @DisplayName("TC-F27-04: Citation preserves exact retrieved snippet")
        void testCitationSnippet() {
            String snippet = "Drop lowest 2 homework assignments";
            Citation citation = new Citation("syllabus.pdf", 2, null, snippet, 0.04);
            assertEquals(snippet, citation.snippet());
        }

        @Test
        @DisplayName("TC-F27-05: Citation contains source file name")
        void testCitationSourceFile() {
            Citation citation = new Citation("resume_v2.pdf", 1, null, "Experienced Engineer", 0.05);
            assertEquals("resume_v2.pdf", citation.sourceFile());
        }
    }

    @Nested
    @DisplayName("F28, F29 & F30: Local Hardware, Test Stubs & Persistence Fallback Tests")
    class LocalHardwareAndFallbackTests {

        @Test
        @DisplayName("TC-F28-01: Loopback guard permits localhost and 127.0.0.1")
        void testLoopbackPermitted() {
            assertDoesNotThrow(() -> LoopbackEnforcingClientStub.verifyLoopbackOnly("http://localhost:11434"));
            assertDoesNotThrow(() -> LoopbackEnforcingClientStub.verifyLoopbackOnly("http://127.0.0.1:11434/api/generate"));
        }

        @Test
        @DisplayName("TC-F28-02: Loopback guard rejects non-loopback cloud hosts (Zero Cloud Leakage)")
        void testCloudHostRejected() {
            assertThrows(SecurityException.class, () ->
                    LoopbackEnforcingClientStub.verifyLoopbackOnly("https://api.openai.com/v1/chat/completions"));
            assertThrows(SecurityException.class, () ->
                    LoopbackEnforcingClientStub.verifyLoopbackOnly("https://api.anthropic.com/v1/messages"));
            assertThrows(SecurityException.class, () ->
                    LoopbackEnforcingClientStub.verifyLoopbackOnly("http://8.8.8.8:11434"));
        }

        @Test
        @DisplayName("TC-F29-01: Deterministic embedding stub generates exact 768-dimensional vector")
        void testDeterministicEmbeddingDimension() {
            float[] vec = LoopbackEnforcingClientStub.generateDeterministicEmbedding("Test string");
            assertNotNull(vec);
            assertEquals(768, vec.length);
        }

        @Test
        @DisplayName("TC-F29-02: Identical text strings produce identical embeddings (deterministic oracle)")
        void testDeterministicEmbeddingParity() {
            float[] vec1 = LoopbackEnforcingClientStub.generateDeterministicEmbedding("Consistent query text");
            float[] vec2 = LoopbackEnforcingClientStub.generateDeterministicEmbedding("Consistent query text");

            assertArrayEquals(vec1, vec2, "Same text must produce byte-identical embeddings");
        }

        @Test
        @DisplayName("TC-F30-01: Pure Java cosine similarity returns 1.0 for identical vectors")
        void testCosineSimilarityIdentical() {
            float[] a = new float[]{1.0f, 0.0f, 0.0f};
            float[] b = new float[]{1.0f, 0.0f, 0.0f};
            assertEquals(1.0, PureJavaVectorStoreStub.computeCosineSimilarity(a, b), 1e-6);
        }

        @Test
        @DisplayName("TC-F30-02: Pure Java cosine similarity returns 0.0 for orthogonal vectors")
        void testCosineSimilarityOrthogonal() {
            float[] a = new float[]{1.0f, 0.0f, 0.0f};
            float[] b = new float[]{0.0f, 1.0f, 0.0f};
            assertEquals(0.0, PureJavaVectorStoreStub.computeCosineSimilarity(a, b), 1e-6);
        }
    }
}
