package com.tracker.rag.e2e.tier1;

import com.tracker.rag.e2e.contract.*;
import com.tracker.rag.e2e.stub.DeterministicDocumentIngestionStub;
import com.tracker.rag.e2e.stub.PureJavaVectorStoreStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Tier 1: Document Ingestion, Parsing & Deduplication Tests (F19-F23)")
public class Tier1IngestionAndStorageTest {

    private PureJavaVectorStoreStub vectorStore;
    private DeterministicDocumentIngestionStub ingestionService;

    @BeforeEach
    void setUp() {
        vectorStore = new PureJavaVectorStoreStub();
        ingestionService = new DeterministicDocumentIngestionStub(vectorStore);
    }

    @Nested
    @DisplayName("F19: Page-Aware PDF Ingestion Tests")
    class PageAwarePdfIngestionTests {

        @Test
        @DisplayName("TC-F19-01: Ingest multi-page PDF preserves 1-indexed page numbers")
        void testMultiPagePdfIngestion() {
            String pdfContent = "Page 1 Content: Course Syllabus\n---PAGE_BREAK---\nPage 2 Content: Grading Policy";
            byte[] bytes = pdfContent.getBytes(StandardCharsets.UTF_8);

            IngestResult result = ingestionService.ingestPdf("syllabus.pdf", DomainScope.ACADEMICS, bytes);
            assertEquals("CREATED", result.status());
            assertEquals(2, result.chunkCount());

            List<VectorChunk> chunks = ingestionService.getStoredChunks("syllabus.pdf", DomainScope.ACADEMICS);
            assertEquals(2, chunks.size());
            assertEquals(1, chunks.get(0).pageNumber());
            assertEquals(2, chunks.get(1).pageNumber());
        }

        @Test
        @DisplayName("TC-F19-02: Single-page PDF has pageNumber = 1")
        void testSinglePagePdf() {
            byte[] bytes = "Single page document text".getBytes(StandardCharsets.UTF_8);
            IngestResult result = ingestionService.ingestPdf("doc.pdf", DomainScope.CAREER, bytes);

            assertEquals(1, result.chunkCount());
            List<VectorChunk> chunks = ingestionService.getStoredChunks("doc.pdf", DomainScope.CAREER);
            assertEquals(1, chunks.get(0).pageNumber());
        }

        @Test
        @DisplayName("TC-F19-03: Empty PDF throws EmptyDocumentException")
        void testEmptyPdfThrows() {
            assertThrows(EmptyDocumentException.class, () ->
                    ingestionService.ingestPdf("empty.pdf", DomainScope.ACADEMICS, new byte[0]));
        }

        @Test
        @DisplayName("TC-F19-04: PDF ingestion without domain scope fails closed")
        void testPdfIngestionNullDomainThrows() {
            byte[] bytes = "Content".getBytes(StandardCharsets.UTF_8);
            assertThrows(UnscopedQueryException.class, () ->
                    ingestionService.ingestPdf("doc.pdf", null, bytes));
        }

        @Test
        @DisplayName("TC-F19-05: PDF chunks are stored in VectorStore with 768-dim embeddings")
        void testPdfChunksStoredWithEmbeddings() {
            byte[] bytes = "Lecture 1 Notes".getBytes(StandardCharsets.UTF_8);
            ingestionService.ingestPdf("lecture1.pdf", DomainScope.ACADEMICS, bytes);

            List<VectorChunk> chunks = ingestionService.getStoredChunks("lecture1.pdf", DomainScope.ACADEMICS);
            assertEquals(1, chunks.size());
            assertNotNull(chunks.get(0).embedding());
            assertEquals(768, chunks.get(0).embedding().length);
        }
    }

    @Nested
    @DisplayName("F20: Heading-Aware Markdown Ingestion Tests")
    class HeadingAwareMarkdownTests {

        @Test
        @DisplayName("TC-F20-01: Parse hierarchical heading breadcrumbs H1 > H2 > H3")
        void testHierarchicalHeadings() {
            String md = """
                    # CS 101
                    Introductory concepts.
                    ## Grading Policy
                    Weight distributions here.
                    ### Final Exam
                    The final exam is worth 30 percent.
                    """;

            IngestResult result = ingestionService.ingestMarkdown("notes.md", DomainScope.ACADEMICS, md);
            assertEquals("CREATED", result.status());

            List<VectorChunk> chunks = ingestionService.getStoredChunks("notes.md", DomainScope.ACADEMICS);
            assertEquals(3, chunks.size());
            assertEquals("CS 101", chunks.get(0).headingPath());
            assertEquals("CS 101 > Grading Policy", chunks.get(1).headingPath());
            assertEquals("CS 101 > Grading Policy > Final Exam", chunks.get(2).headingPath());
        }

        @Test
        @DisplayName("TC-F20-02: Sibling headings pop deeper headings from stack")
        void testSiblingHeadingsStackPopping() {
            String md = """
                    # Main
                    ## Section A
                    Details A.
                    ## Section B
                    Details B.
                    """;

            ingestionService.ingestMarkdown("guide.md", DomainScope.CAREER, md);
            List<VectorChunk> chunks = ingestionService.getStoredChunks("guide.md", DomainScope.CAREER);

            assertEquals("Main > Section A", chunks.get(0).headingPath());
            assertEquals("Main > Section B", chunks.get(1).headingPath());
        }

        @Test
        @DisplayName("TC-F20-03: Markdown without headings has null headingPath")
        void testMarkdownNoHeadings() {
            String md = "Just unstructured text without any markdown hashes.";
            ingestionService.ingestMarkdown("raw.md", DomainScope.PERSONAL, md);

            List<VectorChunk> chunks = ingestionService.getStoredChunks("raw.md", DomainScope.PERSONAL);
            assertEquals(1, chunks.size());
            assertNull(chunks.get(0).headingPath());
        }

        @Test
        @DisplayName("TC-F20-04: Empty markdown string throws EmptyDocumentException")
        void testEmptyMarkdownThrows() {
            assertThrows(EmptyDocumentException.class, () ->
                    ingestionService.ingestMarkdown("empty.md", DomainScope.ACADEMICS, ""));
            assertThrows(EmptyDocumentException.class, () ->
                    ingestionService.ingestMarkdown("empty.md", DomainScope.ACADEMICS, "   "));
        }

        @Test
        @DisplayName("TC-F20-05: Markdown chunks retain 0 for pageNumber")
        void testMarkdownPageNumberZero() {
            String md = "# Resume\nSoftware Engineer profile.";
            ingestionService.ingestMarkdown("resume.md", DomainScope.CAREER, md);

            List<VectorChunk> chunks = ingestionService.getStoredChunks("resume.md", DomainScope.CAREER);
            assertEquals(0, chunks.get(0).pageNumber());
        }
    }

    @Nested
    @DisplayName("F21: Content Hash Deduplication Tests")
    class ContentHashDeduplicationTests {

        @Test
        @DisplayName("TC-F21-01: Re-ingesting identical bytes produces SKIPPED_UNCHANGED")
        void testDuplicateHashSkipsIngestion() {
            byte[] bytes = "Identical syllabus content".getBytes(StandardCharsets.UTF_8);

            IngestResult first = ingestionService.ingestPdf("syllabus.pdf", DomainScope.ACADEMICS, bytes);
            assertEquals("CREATED", first.status());

            IngestResult second = ingestionService.ingestPdf("syllabus.pdf", DomainScope.ACADEMICS, bytes);
            assertEquals("SKIPPED_UNCHANGED", second.status());
            assertEquals(0, second.chunkCount());
            assertEquals(first.fileHash(), second.fileHash());
        }

        @Test
        @DisplayName("TC-F21-02: Re-ingesting identical Markdown produces SKIPPED_UNCHANGED")
        void testDuplicateMarkdownSkips() {
            String md = "# Goals\nWorkout daily.";
            IngestResult r1 = ingestionService.ingestMarkdown("goals.md", DomainScope.PERSONAL, md);
            assertEquals("CREATED", r1.status());

            IngestResult r2 = ingestionService.ingestMarkdown("goals.md", DomainScope.PERSONAL, md);
            assertEquals("SKIPPED_UNCHANGED", r2.status());
        }

        @Test
        @DisplayName("TC-F21-03: Vector store chunk count does not duplicate on skip")
        void testVectorStoreNotDuplicatedOnSkip() {
            byte[] bytes = "Sample data".getBytes(StandardCharsets.UTF_8);
            ingestionService.ingestPdf("doc.pdf", DomainScope.CAREER, bytes);
            assertEquals(1, vectorStore.countChunks(DomainScope.CAREER));

            ingestionService.ingestPdf("doc.pdf", DomainScope.CAREER, bytes);
            assertEquals(1, vectorStore.countChunks(DomainScope.CAREER));
        }

        @Test
        @DisplayName("TC-F21-04: Same content under different paths is treated as distinct documents")
        void testSameContentDifferentPaths() {
            byte[] bytes = "Shared template text".getBytes(StandardCharsets.UTF_8);
            IngestResult r1 = ingestionService.ingestPdf("doc1.pdf", DomainScope.ACADEMICS, bytes);
            IngestResult r2 = ingestionService.ingestPdf("doc2.pdf", DomainScope.ACADEMICS, bytes);

            assertEquals("CREATED", r1.status());
            assertEquals("CREATED", r2.status());
            assertEquals(2, vectorStore.countChunks(DomainScope.ACADEMICS));
        }

        @Test
        @DisplayName("TC-F21-05: Hash hex string is exactly 64 characters (SHA-256)")
        void testSha256HexLength() {
            byte[] bytes = "Testing sha256 output length".getBytes(StandardCharsets.UTF_8);
            IngestResult result = ingestionService.ingestPdf("hash_test.pdf", DomainScope.ACADEMICS, bytes);
            assertNotNull(result.fileHash());
            assertEquals(64, result.fileHash().length());
        }
    }

    @Nested
    @DisplayName("F22 & F23: Transactional Re-Ingestion & Domain-Scoped Storage Tests")
    class ReingestionAndStorageTests {

        @Test
        @DisplayName("TC-F22-01: Modifying document triggers UPDATED status and replaces chunks")
        void testModifiedDocumentReingested() {
            byte[] v1 = "Version 1 Notes".getBytes(StandardCharsets.UTF_8);
            byte[] v2 = "Version 2 Notes Updated with New Syllabus".getBytes(StandardCharsets.UTF_8);

            IngestResult r1 = ingestionService.ingestPdf("syllabus.pdf", DomainScope.ACADEMICS, v1);
            assertEquals("CREATED", r1.status());
            assertEquals(1, vectorStore.countChunks(DomainScope.ACADEMICS));

            IngestResult r2 = ingestionService.ingestPdf("syllabus.pdf", DomainScope.ACADEMICS, v2);
            assertEquals("UPDATED", r2.status());
            assertNotEquals(r1.fileHash(), r2.fileHash());
            assertEquals(1, vectorStore.countChunks(DomainScope.ACADEMICS), "Old chunk must be replaced");

            List<VectorChunk> chunks = ingestionService.getStoredChunks("syllabus.pdf", DomainScope.ACADEMICS);
            assertEquals("Version 2 Notes Updated with New Syllabus", chunks.get(0).content());
        }

        @Test
        @DisplayName("TC-F22-02: Modified Markdown updates heading chunks cleanly")
        void testModifiedMarkdownReingestion() {
            String v1 = "# Term\nWeek 1";
            String v2 = "# Term\nWeek 1\n## Exam\nFinal scheduled";

            ingestionService.ingestMarkdown("term.md", DomainScope.ACADEMICS, v1);
            assertEquals(1, vectorStore.countChunks(DomainScope.ACADEMICS));

            IngestResult r2 = ingestionService.ingestMarkdown("term.md", DomainScope.ACADEMICS, v2);
            assertEquals("UPDATED", r2.status());
            assertEquals(2, vectorStore.countChunks(DomainScope.ACADEMICS));
        }

        @Test
        @DisplayName("TC-F23-01: Chunks are stored with explicit domain scope")
        void testChunksStoredWithDomain() {
            ingestionService.ingestMarkdown("notes.md", DomainScope.ACADEMICS, "# Notes\nStudy content");
            ingestionService.ingestMarkdown("journal.md", DomainScope.PERSONAL, "# Diary\nPrivate thoughts");

            assertEquals(1, vectorStore.countChunks(DomainScope.ACADEMICS));
            assertEquals(1, vectorStore.countChunks(DomainScope.PERSONAL));
            assertEquals(0, vectorStore.countChunks(DomainScope.CAREER));
        }

        @Test
        @DisplayName("TC-F23-02: VectorStore deleteByDocumentId removes only specified document")
        void testDeleteByDocumentId() {
            ingestionService.ingestMarkdown("docA.md", DomainScope.ACADEMICS, "# A\nContent A");
            ingestionService.ingestMarkdown("docB.md", DomainScope.ACADEMICS, "# B\nContent B");

            assertEquals(2, vectorStore.countChunks(DomainScope.ACADEMICS));
            vectorStore.deleteByDocumentId("docA.md");

            assertEquals(1, vectorStore.countChunks(DomainScope.ACADEMICS));
        }

        @Test
        @DisplayName("TC-F23-03: Re-ingestion preserves domain scoping integrity")
        void testReingestionPreservesDomain() {
            ingestionService.ingestMarkdown("career.md", DomainScope.CAREER, "# Resume\nProfile 1");
            ingestionService.ingestMarkdown("career.md", DomainScope.CAREER, "# Resume\nProfile 2 Updated");

            assertEquals(1, vectorStore.countChunks(DomainScope.CAREER));
            assertEquals(0, vectorStore.countChunks(DomainScope.PERSONAL));
            assertEquals(0, vectorStore.countChunks(DomainScope.ACADEMICS));
        }
    }
}
