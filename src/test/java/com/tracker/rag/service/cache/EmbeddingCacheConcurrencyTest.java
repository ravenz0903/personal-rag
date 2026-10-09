package com.tracker.rag.service.cache;

import com.tracker.rag.domain.DomainScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EmbeddingCache High-Concurrency & Stress Test Suite (50+ Threads)")
public class EmbeddingCacheConcurrencyTest {

    private static final int CONCURRENCY_THREADS = 64;
    private static final int ITERATIONS_PER_THREAD = 500;

    @Test
    @DisplayName("TC-CONCUR-01: 64 concurrent threads reading/writing across 3 domains with zero errors")
    void testConcurrentReadWriteAcrossDomains() throws InterruptedException {
        EmbeddingCache cache = new CaffeineEmbeddingCache(1000);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY_THREADS);

        CountDownLatch readyLatch = new CountDownLatch(CONCURRENCY_THREADS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENCY_THREADS);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        DomainScope[] domains = DomainScope.values();

        for (int t = 0; t < CONCURRENCY_THREADS; t++) {
            final int threadId = t;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
                        DomainScope domain = domains[(threadId + i) % domains.length];
                        String key = "query-" + (i % 50);

                        if (threadId % 2 == 0) {
                            // Writer thread
                            float[] vec = new float[]{(float) threadId, (float) i};
                            cache.put(domain, key, vec);
                        } else {
                            // Reader thread
                            cache.get(domain, key);
                        }
                    }
                } catch (Throwable ex) {
                    errors.add(ex);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Release all threads simultaneously

        boolean completedInTime = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(completedInTime).as("All 64 threads should complete within 15 seconds without deadlocks").isTrue();
        assertThat(errors).as("No exceptions should be thrown during concurrent access").isEmpty();
        assertThat(cache.getStats().size()).isLessThanOrEqualTo(1000);
    }

    @Test
    @DisplayName("TC-CONCUR-02: Thundering herd simulation on a single hot key completes safely")
    void testThunderingHerdOnSingleKey() throws InterruptedException {
        EmbeddingCache cache = new CaffeineEmbeddingCache(100);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY_THREADS);

        CountDownLatch readyLatch = new CountDownLatch(CONCURRENCY_THREADS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENCY_THREADS);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        String hotKey = "popular machine learning query";
        DomainScope domain = DomainScope.ACADEMICS;

        for (int t = 0; t < CONCURRENCY_THREADS; t++) {
            final int threadId = t;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    for (int i = 0; i < 100; i++) {
                        var opt = cache.get(domain, hotKey);
                        if (opt.isEmpty()) {
                            cache.put(domain, hotKey, new float[]{(float) threadId, 42.0f});
                        }
                    }
                } catch (Throwable ex) {
                    errors.add(ex);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(completed).isTrue();
        assertThat(errors).isEmpty();
        assertThat(cache.get(domain, hotKey)).isPresent();
    }

    @Test
    @DisplayName("TC-CONCUR-03: Eviction thrashing under heavy concurrent writes never exceeds bounded capacity")
    void testBoundedCapacityUnderThrashing() throws InterruptedException {
        int boundedCapacity = 50;
        EmbeddingCache cache = new CaffeineEmbeddingCache(boundedCapacity);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY_THREADS);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENCY_THREADS);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int t = 0; t < CONCURRENCY_THREADS; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < 200; i++) {
                        cache.put(DomainScope.ACADEMICS, "t" + threadId + "-k" + i, new float[]{(float) i});
                    }
                } catch (Throwable ex) {
                    errors.add(ex);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(completed).isTrue();
        assertThat(errors).isEmpty();
        assertThat(cache.getStats().size())
                .as("Cache size must strictly never exceed configured capacity")
                .isLessThanOrEqualTo(boundedCapacity);
        assertThat(cache.getStats().evictions())
                .as("Must have evicted excess items")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("TC-CONCUR-04: Vector data integrity under concurrent writes prevents torn reads")
    void testVectorDataIntegrityAntiTornRead() throws InterruptedException {
        EmbeddingCache cache = new CaffeineEmbeddingCache(500);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY_THREADS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENCY_THREADS);
        ConcurrentLinkedQueue<String> corruptedReads = new ConcurrentLinkedQueue<>();

        for (int t = 0; t < CONCURRENCY_THREADS; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < 300; i++) {
                        String key = "key-" + (i % 20);
                        float tag = (float) threadId;
                        float[] vec = new float[768];
                        for (int j = 0; j < 768; j++) vec[j] = tag;

                        cache.put(DomainScope.CAREER, key, vec);

                        var opt = cache.get(DomainScope.CAREER, key);
                        if (opt.isPresent()) {
                            float[] retrieved = opt.get();
                            float first = retrieved[0];
                            for (int j = 1; j < retrieved.length; j++) {
                                if (retrieved[j] != first) {
                                    corruptedReads.add("Torn read detected at idx " + j + ": expected " + first + " got " + retrieved[j]);
                                    break;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    corruptedReads.add(e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(corruptedReads).as("Zero torn reads or corrupted vectors allowed").isEmpty();
    }

    @Test
    @DisplayName("TC-CONCUR-05: 100 Virtual Threads executing concurrent cache operations")
    void testVirtualThreadsConcurrency() throws InterruptedException {
        EmbeddingCache cache = new CaffeineEmbeddingCache(200);
        int virtualThreadCount = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(virtualThreadCount);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        try (ExecutorService vExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < virtualThreadCount; t++) {
                final int id = t;
                vExecutor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < 100; i++) {
                            DomainScope d = (id % 2 == 0) ? DomainScope.ACADEMICS : DomainScope.PERSONAL;
                            String k = "vt-" + (i % 25);
                            cache.put(d, k, new float[]{(float) i, (float) id});
                            cache.get(d, k);
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(errors).isEmpty();
            assertThat(cache.size()).isLessThanOrEqualTo(200);
        }
    }
}
