package com.tracker.rag.service.ingestion;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.domain.UnscopedQueryException;
import com.tracker.rag.dto.EmptyDocumentException;
import com.tracker.rag.dto.IngestResult;
import com.tracker.rag.repository.vector.EmbeddedVectorStore;
import com.tracker.rag.repository.vector.VectorChunk;
import com.tracker.rag.repository.vector.VectorStore;
import com.tracker.rag.service.OllamaClientService;
import com.tracker.rag.service.retrieval.InvertedIndexService;
import com.tracker.rag.service.retrieval.InvertedIndexServiceImpl;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ParallelDocumentIngestionService - Unit, Boundary, and Concurrency Tests")
public class ParallelDocumentIngestionServiceTest {

    private VectorStore vectorStore;
    private InvertedIndexService invertedIndexService;
    private OllamaClientService mockOllamaClient;
    private DocumentIngestionService ingestionService;

    private static class StubOllamaClientService extends OllamaClientService {
        public StubOllamaClientService() {
            super(new com.fasterxml.jackson.databind.ObjectMapper());
        }

        @Override
        public float[] generateEmbedding(String text, DomainScope domain) {
            DomainScope.validateScope(domain);
            return OllamaClientService.generateDeterministicFallbackEmbedding(text);
        }
    }

    @BeforeEach
    void setUp() {
        vectorStore = new EmbeddedVectorStore();
        invertedIndexService = new InvertedIndexServiceImpl();
        mockOllamaClient = new StubOllamaClientService();

        ingestionService = new DocumentIngestionService(vectorStore, null, mockOllamaClient, invertedIndexService);
    }

    private byte[] createSyntheticMultiPagePdf(int pageCount) {
        StringBuilder sb = new StringBuilder();
        for (int p = 1; p <= pageCount; p++) {
            if (p > 1) sb.append("\n---PAGE_BREAK---\n");
            sb.append("Page ").append(p).append(" text content for automated test verification.");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] createRealPdfBoxDocument(int pageCount) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int p = 1; p <= pageCount; p++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText("Real PDFBox Page " + p + " Content: Advanced algorithms and data structures.");
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("Verify multi-page PDF ingestion preserves 1-indexed page numbering and deterministic ordering")
    void testMultiPagePdf1IndexedPreservation() {
        int pageCount = 8;
        byte[] pdfBytes = createSyntheticMultiPagePdf(pageCount);
        String docPath = "courses/cs3500_notes.pdf";

        IngestResult result = ingestionService.ingestPdf(docPath, DomainScope.ACADEMICS, pdfBytes);

        assertThat(result.status()).isEqualTo("CREATED");
        assertThat(result.chunksCreated()).isEqualTo(pageCount);

        List<VectorChunk> chunks = ingestionService.getStoredChunks(docPath, DomainScope.ACADEMICS);
        assertThat(chunks).hasSize(pageCount);

        for (int i = 0; i < pageCount; i++) {
            VectorChunk chunk = chunks.get(i);
            assertThat(chunk.pageNumber()).isEqualTo(i + 1);
            assertThat(chunk.headingPath()).isNull();
            assertThat(chunk.content()).contains("Page " + (i + 1));
            assertThat(chunk.embedding()).isNotNull().hasSize(768);
        }
    }

    @Test
    @DisplayName("Verify real Apache PDFBox multi-page document extracts and embeds correctly via virtual threads")
    void testRealPdfBoxDocumentExtractionAndEmbedding() throws IOException {
        int pageCount = 4;
        byte[] pdfBytes = createRealPdfBoxDocument(pageCount);
        String docPath = "courses/algorithms_syllabus.pdf";

        IngestResult result = ingestionService.ingestPdf(docPath, DomainScope.ACADEMICS, pdfBytes);

        assertThat(result.status()).isEqualTo("CREATED");
        assertThat(result.chunksCreated()).isEqualTo(pageCount);

        List<VectorChunk> chunks = ingestionService.getStoredChunks(docPath, DomainScope.ACADEMICS);
        assertThat(chunks).hasSize(pageCount);

        for (int i = 0; i < pageCount; i++) {
            VectorChunk chunk = chunks.get(i);
            assertThat(chunk.pageNumber()).isEqualTo(i + 1);
            assertThat(chunk.content()).contains("Real PDFBox Page " + (i + 1));
        }
    }

    @Test
    @DisplayName("Verify Markdown ingestion parses heading hierarchy and preserves breadcrumbs deterministically")
    void testMarkdownHeadingHierarchyBreadcrumbsPreserved() {
        String markdown = """
                # CS 3500 Object-Oriented Design
                General course overview and prerequisites.
                ## Grading Policy
                Assessment breakdown and weighted criteria.
                ### Exams
                Midterm and final exam policy and logistics.
                ### Homework
                Weekly assignments submitted via Github Classroom.
                ## Office Hours
                Instructor and TA consulting schedules.
                """;

        String docPath = "courses/cs3500_syllabus.md";
        IngestResult result = ingestionService.ingestMarkdown(docPath, DomainScope.ACADEMICS, markdown);

        assertThat(result.status()).isEqualTo("CREATED");
        assertThat(result.chunksCreated()).isEqualTo(5);

        List<VectorChunk> chunks = ingestionService.getStoredChunks(docPath, DomainScope.ACADEMICS);
        assertThat(chunks).hasSize(5);

        assertThat(chunks.get(0).headingPath()).isEqualTo("CS 3500 Object-Oriented Design");
        assertThat(chunks.get(0).pageNumber()).isEqualTo(0);
        assertThat(chunks.get(0).content()).contains("General course overview");

        assertThat(chunks.get(1).headingPath()).isEqualTo("CS 3500 Object-Oriented Design > Grading Policy");
        assertThat(chunks.get(1).content()).contains("Assessment breakdown");

        assertThat(chunks.get(2).headingPath()).isEqualTo("CS 3500 Object-Oriented Design > Grading Policy > Exams");
        assertThat(chunks.get(2).content()).contains("Midterm and final exam policy");

        assertThat(chunks.get(3).headingPath()).isEqualTo("CS 3500 Object-Oriented Design > Grading Policy > Homework");
        assertThat(chunks.get(3).content()).contains("Weekly assignments");

        assertThat(chunks.get(4).headingPath()).isEqualTo("CS 3500 Object-Oriented Design > Office Hours");
        assertThat(chunks.get(4).content()).contains("Instructor and TA consulting");

    }

    @Test
    @DisplayName("Verify SHA-256 deduplication skips re-ingestion of unchanged document")
    void testSha256DeduplicationMatch() {
        byte[] pdfBytes = createSyntheticMultiPagePdf(5);
        String docPath = "courses/dedup_document.pdf";

        IngestResult r1 = ingestionService.ingestPdf(docPath, DomainScope.CAREER, pdfBytes);
        assertThat(r1.status()).isEqualTo("CREATED");
        assertThat(r1.chunksCreated()).isEqualTo(5);

        IngestResult r2 = ingestionService.ingestPdf(docPath, DomainScope.CAREER, pdfBytes);
        assertThat(r2.status()).isEqualTo("SKIPPED");
        assertThat(r2.chunksCreated()).isEqualTo(0);
        assertThat(r2.contentHash()).isEqualTo(r1.contentHash());
    }

    @Test
    @DisplayName("Verify transactional re-ingestion purges previous chunks and updates InvertedIndexService")
    void testTransactionalReingestionLifecycle() {
        byte[] v1 = createSyntheticMultiPagePdf(6);
        String docPath = "courses/transactional_test.pdf";

        IngestResult r1 = ingestionService.ingestPdf(docPath, DomainScope.ACADEMICS, v1);
        assertThat(r1.status()).isEqualTo("CREATED");
        assertThat(vectorStore.countByDomain(DomainScope.ACADEMICS)).isEqualTo(6);
        assertThat(invertedIndexService.size(DomainScope.ACADEMICS)).isEqualTo(6);

        // Update to 3 pages
        byte[] v2 = createSyntheticMultiPagePdf(3);
        IngestResult r2 = ingestionService.ingestPdf(docPath, DomainScope.ACADEMICS, v2);
        assertThat(r2.status()).isEqualTo("UPDATED");
        assertThat(r2.chunksCreated()).isEqualTo(3);

        assertThat(vectorStore.countByDomain(DomainScope.ACADEMICS)).isEqualTo(3);
        assertThat(invertedIndexService.size(DomainScope.ACADEMICS)).isEqualTo(3);
        List<VectorChunk> chunks = ingestionService.getStoredChunks(docPath, DomainScope.ACADEMICS);
        assertThat(chunks).hasSize(3);
    }

    @Test
    @DisplayName("Verify ParallelDocumentIngestionService ingestPdfParallel via InputStream")
    void testIngestPdfParallelViaInputStream() {
        byte[] pdfBytes = createSyntheticMultiPagePdf(4);
        ByteArrayInputStream in = new ByteArrayInputStream(pdfBytes);

        IngestResult result = ingestionService.ingestPdfParallel(in, "docs/parallel_test.pdf", DomainScope.PERSONAL);

        assertThat(result.status()).isEqualTo("CREATED");
        assertThat(result.chunksCreated()).isEqualTo(4);
        assertThat(vectorStore.countByDomain(DomainScope.PERSONAL)).isEqualTo(4);
    }

    @Test
    @DisplayName("Verify ParallelDocumentIngestionService ingestMarkdownParallel")
    void testIngestMarkdownParallel() {
        String md = "# Personal Goals\nStay healthy and consistent.\n## Fitness\nRun 5k every morning.";
        IngestResult result = ingestionService.ingestMarkdownParallel(md, "notes/goals.md", DomainScope.PERSONAL);

        assertThat(result.status()).isEqualTo("CREATED");
        assertThat(result.chunksCreated()).isEqualTo(2);
        assertThat(vectorStore.countByDomain(DomainScope.PERSONAL)).isEqualTo(2);
    }

    @Test
    @DisplayName("Verify ParallelDocumentIngestionService ingestPdfAsync returns completed CompletableFuture")
    void testIngestPdfAsyncCompletableFuture() throws ExecutionException, InterruptedException {
        byte[] pdfBytes = createSyntheticMultiPagePdf(5);
        ByteArrayInputStream in = new ByteArrayInputStream(pdfBytes);

        CompletableFuture<IngestResult> future = ingestionService.ingestPdfAsync(in, "docs/async_test.pdf", DomainScope.CAREER);
        IngestResult result = future.get();

        assertThat(result.status()).isEqualTo("CREATED");
        assertThat(result.chunksCreated()).isEqualTo(5);
        assertThat(vectorStore.countByDomain(DomainScope.CAREER)).isEqualTo(5);
    }

    @Test
    @DisplayName("Verify fail-closed scope validation on null domain")
    void testFailClosedOnNullDomain() {
        byte[] bytes = createSyntheticMultiPagePdf(2);

        assertThatThrownBy(() -> ingestionService.ingestPdf("test.pdf", null, bytes))
                .isInstanceOf(UnscopedQueryException.class);

        assertThatThrownBy(() -> ingestionService.ingestMarkdown("test.md", null, "# Hello\nWorld"))
                .isInstanceOf(UnscopedQueryException.class);

        assertThatThrownBy(() -> ingestionService.ingestPdfParallel(new ByteArrayInputStream(bytes), "test.pdf", null))
                .isInstanceOf(UnscopedQueryException.class);

        assertThatThrownBy(() -> ingestionService.ingestPdfAsync(new ByteArrayInputStream(bytes), "test.pdf", null))
                .isInstanceOf(UnscopedQueryException.class);
    }

    @Test
    @DisplayName("Verify EmptyDocumentException on zero-byte PDF or empty Markdown")
    void testEmptyDocumentThrows() {
        assertThatThrownBy(() -> ingestionService.ingestPdf("empty.pdf", DomainScope.ACADEMICS, new byte[0]))
                .isInstanceOf(EmptyDocumentException.class);

        assertThatThrownBy(() -> ingestionService.ingestPdf("null.pdf", DomainScope.ACADEMICS, null))
                .isInstanceOf(EmptyDocumentException.class);

        assertThatThrownBy(() -> ingestionService.ingestMarkdown("empty.md", DomainScope.ACADEMICS, "   \n\t  "))
                .isInstanceOf(EmptyDocumentException.class);

        assertThatThrownBy(() -> ingestionService.ingestPdfParallel(null, "null.pdf", DomainScope.ACADEMICS))
                .isInstanceOf(EmptyDocumentException.class);
    }

    @Test
    @DisplayName("Verify concurrent document ingestion across multiple virtual threads and domains")
    void testConcurrentMultiDocumentIngestion() throws InterruptedException {
        int docCount = 12;
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(docCount);
        List<Future<IngestResult>> futures = new ArrayList<>();

        DomainScope[] domains = {DomainScope.ACADEMICS, DomainScope.PERSONAL, DomainScope.CAREER};

        for (int i = 0; i < docCount; i++) {
            final int id = i;
            final DomainScope domain = domains[i % domains.length];
            byte[] bytes = createSyntheticMultiPagePdf(3);
            futures.add(executor.submit(() -> {
                try {
                    return ingestionService.ingestPdf("doc_" + id + ".pdf", domain, bytes);
                } finally {
                    latch.countDown();
                }
            }));
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        for (Future<IngestResult> future : futures) {
            assertThat(future).isDone();
        }

        // Each of the 12 documents has 3 chunks = 36 chunks total
        long totalChunks = vectorStore.countByDomain(DomainScope.ACADEMICS)
                + vectorStore.countByDomain(DomainScope.PERSONAL)
                + vectorStore.countByDomain(DomainScope.CAREER);

        assertThat(totalChunks).isEqualTo(36);
        executor.close();
    }
}
