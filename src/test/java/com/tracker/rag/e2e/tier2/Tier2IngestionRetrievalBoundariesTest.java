package com.tracker.rag.e2e.tier2;

import com.tracker.rag.e2e.contract.*;
import com.tracker.rag.e2e.stub.DeterministicDocumentIngestionStub;
import com.tracker.rag.e2e.stub.DeterministicHybridRetrieverStub;
import com.tracker.rag.e2e.stub.LoopbackEnforcingClientStub;
import com.tracker.rag.e2e.stub.PureJavaVectorStoreStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Tier 2: Boundary & Corner Cases - Ingestion, Hybrid Search & Hardware Security (F19-F30)")
public class Tier2IngestionRetrievalBoundariesTest {

    private PureJavaVectorStoreStub vectorStore;
    private DeterministicDocumentIngestionStub ingestionService;
    private List<VectorChunk> chunkList;

    @BeforeEach
    void setUp() {
        vectorStore = new PureJavaVectorStoreStub();
        ingestionService = new DeterministicDocumentIngestionStub(vectorStore);
        chunkList = new ArrayList<>();
    }

    @Nested
    @DisplayName("Document Parsing & Deduplication Boundary Tests")
    class DocumentParsingBoundaries {

        @Test
        @DisplayName("BC-F19-01: 0-byte PDF throws EmptyDocumentException")
        void testZeroBytePdfThrows() {
            assertThrows(EmptyDocumentException.class, () ->
                    ingestionService.ingestPdf("zero.pdf", DomainScope.ACADEMICS, new byte[0]));
        }

        @Test
        @DisplayName("BC-F20-01: Markdown with 8 levels of deep headings parses breadcrumb path")
        void testDeeplyNestedHeadings() {
            String deepMd = """
                    # L1
                    ## L2
                    ### L3
                    #### L4
                    ##### L5
                    ###### L6
                    ####### L7
                    ######## L8
                    Deep leaf content.
                    """;

            IngestResult result = ingestionService.ingestMarkdown("deep.md", DomainScope.ACADEMICS, deepMd);
            assertEquals("CREATED", result.status());

            List<VectorChunk> chunks = ingestionService.getStoredChunks("deep.md", DomainScope.ACADEMICS);
            assertEquals(1, chunks.size());
            assertEquals("L1 > L2 > L3 > L4 > L5 > L6 > L7 > L8", chunks.get(0).headingPath());
        }

        @Test
        @DisplayName("BC-F20-02: Duplicate heading names at different nesting levels are disambiguated by path")
        void testDuplicateHeadingsDisambiguated() {
            String md = """
                    # Week 1
                    ## Homework
                    Week 1 HW
                    # Week 2
                    ## Homework
                    Week 2 HW
                    """;

            ingestionService.ingestMarkdown("syllabus.md", DomainScope.ACADEMICS, md);
            List<VectorChunk> chunks = ingestionService.getStoredChunks("syllabus.md", DomainScope.ACADEMICS);

            assertEquals(2, chunks.size());
            assertEquals("Week 1 > Homework", chunks.get(0).headingPath());
            assertEquals("Week 2 > Homework", chunks.get(1).headingPath());
        }

        @Test
        @DisplayName("BC-F21-01: 1-byte difference in document triggers re-ingestion (UPDATED status)")
        void testSingleByteDifferenceReingests() {
            byte[] v1 = "Version 1.0".getBytes(StandardCharsets.UTF_8);
            byte[] v2 = "Version 1.1".getBytes(StandardCharsets.UTF_8);

            IngestResult r1 = ingestionService.ingestPdf("spec.pdf", DomainScope.CAREER, v1);
            assertEquals("CREATED", r1.status());

            IngestResult r2 = ingestionService.ingestPdf("spec.pdf", DomainScope.CAREER, v2);
            assertEquals("UPDATED", r2.status());
            assertNotEquals(r1.fileHash(), r2.fileHash());
        }
    }

    @Nested
    @DisplayName("Hybrid Retrieval & Vector Math Boundary Tests")
    class HybridRetrievalBoundaries {

        @Test
        @DisplayName("BC-F24-01: Pure Java Cosine similarity handles zero vectors cleanly")
        void testZeroVectorsCosine() {
            float[] zero = new float[]{0.0f, 0.0f, 0.0f};
            float[] nonZero = new float[]{1.0f, 2.0f, 3.0f};

            assertEquals(0.0, PureJavaVectorStoreStub.computeCosineSimilarity(zero, nonZero));
            assertEquals(0.0, PureJavaVectorStoreStub.computeCosineSimilarity(zero, zero));
        }

        @Test
        @DisplayName("BC-F24-02: Cosine similarity returns exact 1.0 for parallel vectors")
        void testParallelVectorsCosine() {
            float[] a = new float[]{2.0f, 4.0f, 6.0f};
            float[] b = new float[]{1.0f, 2.0f, 3.0f};

            assertEquals(1.0, PureJavaVectorStoreStub.computeCosineSimilarity(a, b), 1e-6);
        }

        @Test
        @DisplayName("BC-F26-01: Candidate pool smaller than topK returns all available candidates")
        void testCandidatePoolSmallerThanK() {
            float[] emb = LoopbackEnforcingClientStub.generateDeterministicEmbedding("Single chunk");
            VectorChunk chunk = new VectorChunk(UUID.randomUUID(), "doc.pdf", DomainScope.ACADEMICS, 1, null, "Single chunk", emb);
            vectorStore.save(chunk);
            chunkList.add(chunk);

            DeterministicHybridRetrieverStub retriever = new DeterministicHybridRetrieverStub(vectorStore, chunkList);
            HybridSearchResult result = retriever.search("Single chunk", DomainScope.ACADEMICS, 10);

            assertEquals(1, result.citations().size(), "Should return available chunk without padding");
        }

        @Test
        @DisplayName("BC-F27-01: Citation snippet preservation with special unicode characters")
        void testCitationUnicodeSnippet() {
            Citation c = new Citation("notes.md", 0, "Math", "Formula: ∑ (x_i × w_i) ≥ 90.00%", 0.05);
            assertTrue(c.snippet().contains("∑"));
            assertTrue(c.snippet().contains("≥"));
        }
    }

    @Nested
    @DisplayName("Zero Cloud Leakage & Network Boundary Tests")
    class ZeroCloudLeakageBoundaries {

        @Test
        @DisplayName("BC-F28-01: Non-loopback IP (192.168.1.50) throws SecurityException")
        void testPrivateNonLoopbackIpRejected() {
            assertThrows(SecurityException.class, () ->
                    LoopbackEnforcingClientStub.verifyLoopbackOnly("http://192.168.1.50:11434"));
        }

        @Test
        @DisplayName("BC-F28-02: Public cloud domain throws SecurityException")
        void testPublicCloudHostRejected() {
            assertThrows(SecurityException.class, () ->
                    LoopbackEnforcingClientStub.verifyLoopbackOnly("https://api.ollama.ai/v1/generate"));
        }

        @Test
        @DisplayName("BC-F28-03: IPv6 loopback [::1] permitted")
        void testIpv6LoopbackPermitted() {
            assertDoesNotThrow(() ->
                    LoopbackEnforcingClientStub.verifyLoopbackOnly("http://[::1]:11434/api/generate"));
        }
    }
}
