package com.tracker.rag.service.retrieval;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.repository.vector.VectorChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InvertedIndexService Concurrency & Virtual Thread Stress Tests")
public class InvertedIndexConcurrencyTest {

    private InvertedIndexServiceImpl indexService;
    private static final float[] DUMMY_EMBEDDING = new float[768];

    @BeforeEach
    void setUp() {
        indexService = new InvertedIndexServiceImpl(null);
    }

    private VectorChunk createChunk(String docId, DomainScope domain, int page, String content) {
        return new VectorChunk(UUID.randomUUID(), docId, domain, page, "H1 > Concurrency", content, DUMMY_EMBEDDING);
    }

    @Test
    @DisplayName("50+ concurrent virtual threads executing queries simultaneously without deadlocks or race conditions")
    void test50ConcurrentVirtualThreadReaders() throws InterruptedException {
        // Pre-populate index with 300 chunks across domains
        for (int i = 0; i < 300; i++) {
            DomainScope domain = (i % 3 == 0) ? DomainScope.ACADEMICS :
                    (i % 3 == 1) ? DomainScope.PERSONAL : DomainScope.CAREER;
            indexService.indexChunk(createChunk("doc_" + i, domain, 1,
                    "Virtual thread concurrent reader stress testing chunk payload " + i));
        }

        int threadCount = 60; // 60 concurrent virtual threads
        int queriesPerThread = 50;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger totalSuccessfulQueries = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        DomainScope domain = (threadId % 3 == 0) ? DomainScope.ACADEMICS :
                                (threadId % 3 == 1) ? DomainScope.PERSONAL : DomainScope.CAREER;
                        for (int q = 0; q < queriesPerThread; q++) {
                            var results = indexService.search("concurrent reader stress", domain, 10);
                            assertNotNull(results);
                            assertFalse(results.isEmpty(), "Must find matches in domain " + domain);
                            totalSuccessfulQueries.incrementAndGet();
                        }
                    } catch (Throwable e) {
                        failureCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }

        boolean completedInTime = latch.await(15, TimeUnit.SECONDS);
        assertTrue(completedInTime, "All virtual threads must complete within timeout");
        assertEquals(0, failureCount.get(), "Zero exceptions or assertion failures allowed");
        assertEquals(threadCount * queriesPerThread, totalSuccessfulQueries.get());
    }

    @Test
    @DisplayName("Interleaved concurrent readers and writers across 80 virtual threads maintain index integrity")
    void testConcurrentReadWriteInterleaving() throws InterruptedException {
        int writerThreads = 20;
        int readerThreads = 60;
        int totalThreads = writerThreads + readerThreads;
        CountDownLatch latch = new CountDownLatch(totalThreads);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicInteger writeOps = new AtomicInteger(0);
        AtomicInteger readOps = new AtomicInteger(0);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Writers: repeatedly indexing and removing documents
            for (int w = 0; w < writerThreads; w++) {
                final int writerId = w;
                executor.submit(() -> {
                    try {
                        DomainScope domain = (writerId % 2 == 0) ? DomainScope.ACADEMICS : DomainScope.CAREER;
                        for (int i = 0; i < 40; i++) {
                            String docId = "writer_doc_" + writerId + "_" + i;
                            VectorChunk chunk = createChunk(docId, domain, 1,
                                    "Interleaved concurrent dynamic stream entry " + i + " by writer " + writerId);
                            indexService.indexChunk(chunk);
                            writeOps.incrementAndGet();

                            if (i % 4 == 0) {
                                indexService.removeDocument(docId, domain);
                                writeOps.incrementAndGet();
                            }
                        }
                    } catch (Throwable t) {
                        errorCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Readers: continuously querying across all domains
            for (int r = 0; r < readerThreads; r++) {
                final int readerId = r;
                executor.submit(() -> {
                    try {
                        DomainScope domain = (readerId % 2 == 0) ? DomainScope.ACADEMICS : DomainScope.CAREER;
                        for (int i = 0; i < 50; i++) {
                            var results = indexService.search("concurrent dynamic stream", domain, 10);
                            assertNotNull(results);
                            readOps.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        errorCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }

        boolean finished = latch.await(20, TimeUnit.SECONDS);
        assertTrue(finished, "Interleaved readers and writers must finish within timeout");
        assertEquals(0, errorCount.get(), "Concurrent readers and writers must produce 0 errors");
        assertTrue(writeOps.get() > 0);
        assertTrue(readOps.get() > 0);
    }

    @Test
    @DisplayName("Cross-domain concurrent writes do not deadlock each other")
    void testCrossDomainDeadlockFreedom() throws InterruptedException {
        int threadsPerDomain = 25;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadsPerDomain * 3);
        AtomicInteger errors = new AtomicInteger(0);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (DomainScope domain : DomainScope.values()) {
                for (int i = 0; i < threadsPerDomain; i++) {
                    final int id = i;
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            for (int k = 0; k < 20; k++) {
                                String docId = "batch_doc_" + domain + "_" + id + "_" + k;
                                indexService.indexChunk(createChunk(docId, domain, 1, "Deadlock avoidance payload " + k));
                                indexService.search("deadlock avoidance", domain, 5);
                                if (k % 5 == 0) {
                                    indexService.removeDocument(docId, domain);
                                }
                            }
                        } catch (Throwable t) {
                            errors.incrementAndGet();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }
            }
            startLatch.countDown(); // Release all virtual threads simultaneously
        }

        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
        assertTrue(completed, "Cross-domain operations must not deadlock");
        assertEquals(0, errors.get(), "Zero errors expected in cross-domain concurrency");
    }
}
