package com.tracker.rag.service.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.domain.InvalidDomainException;
import com.tracker.rag.domain.UnscopedQueryException;
import com.tracker.rag.service.OllamaClientService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Adversarial Cache Boundary & Domain Isolation Stress Test Suite")
public class AdversarialCacheBoundaryTest {

    // =========================================================================
    // 1. DOMAIN ISOLATION & OPT-IN BOUNDARY
    // =========================================================================
    @Nested
    @DisplayName("1. Domain Isolation Verification")
    class DomainIsolationVerification {

        @Test
        @DisplayName("ADV-01: Identical text across ACADEMICS, PERSONAL, and CAREER is strictly isolated")
        void testIdenticalTextCrossDomainIsolation() {
            EmbeddingCache cache = new CaffeineEmbeddingCache(1000);
            String sensitiveQuery = "Confidential psychological counseling records and GPA targets";

            float[] personalVector = new float[768];
            Arrays.fill(personalVector, 1.11f);

            float[] academicVector = new float[768];
            Arrays.fill(academicVector, 2.22f);

            float[] careerVector = new float[768];
            Arrays.fill(careerVector, 3.33f);

            // Store strictly under PERSONAL
            cache.put(DomainScope.PERSONAL, sensitiveQuery, personalVector);

            // Academics and Career queries must miss and NEVER return personal data
            assertThat(cache.get(DomainScope.ACADEMICS, sensitiveQuery))
                    .as("ACADEMICS query for text stored in PERSONAL must return empty")
                    .isEmpty();
            assertThat(cache.get(DomainScope.CAREER, sensitiveQuery))
                    .as("CAREER query for text stored in PERSONAL must return empty")
                    .isEmpty();
            assertThat(cache.get(DomainScope.PERSONAL, sensitiveQuery))
                    .as("PERSONAL query must return the personal vector")
                    .isPresent()
                    .contains(personalVector);

            // Now store separate vectors under ACADEMICS and CAREER for identical text
            cache.put(DomainScope.ACADEMICS, sensitiveQuery, academicVector);
            cache.put(DomainScope.CAREER, sensitiveQuery, careerVector);

            // Verify each domain returns ONLY its own isolated vector
            assertThat(cache.get(DomainScope.PERSONAL, sensitiveQuery).orElseThrow())
                    .as("PERSONAL vector must remain intact")
                    .isEqualTo(personalVector)
                    .isNotEqualTo(academicVector);

            assertThat(cache.get(DomainScope.ACADEMICS, sensitiveQuery).orElseThrow())
                    .as("ACADEMICS vector must match academic vector and never personal")
                    .isEqualTo(academicVector)
                    .isNotEqualTo(personalVector);

            assertThat(cache.get(DomainScope.CAREER, sensitiveQuery).orElseThrow())
                    .as("CAREER vector must match career vector and never personal")
                    .isEqualTo(careerVector)
                    .isNotEqualTo(personalVector);
        }

        @Test
        @DisplayName("ADV-02: OllamaClientService strictly isolates domains for identical query text")
        void testOllamaClientServiceDomainIsolation() {
            EmbeddingCache cache = new CaffeineEmbeddingCache(1000);
            OllamaClientService client = new OllamaClientService(new ObjectMapper(), cache);

            String text = "Machine Learning optimization and gradient descent";

            // Scoped call for ACADEMICS
            float[] academicEmb = client.generateEmbedding(text, DomainScope.ACADEMICS);
            assertThat(academicEmb).isNotNull().hasSize(768);

            // Direct cache gets: PERSONAL and CAREER miss, ACADEMICS hits
            assertThat(cache.get(DomainScope.PERSONAL, text)).isEmpty();
            assertThat(cache.get(DomainScope.CAREER, text)).isEmpty();
            assertThat(cache.get(DomainScope.ACADEMICS, text)).isPresent();

            // Calling generateEmbedding for PERSONAL computes and stores under PERSONAL separately
            float[] personalEmb = client.generateEmbedding(text, DomainScope.PERSONAL);
            assertThat(personalEmb).isNotNull().hasSize(768);

            // Verify stats: 4 total misses (client ACADEMICS miss + 2 direct get misses + client PERSONAL miss)
            // and 1 hit (direct get ACADEMICS)
            EmbeddingCache.CacheStats stats = cache.getStats();
            assertThat(stats.misses()).isEqualTo(4);
            assertThat(stats.hits()).isEqualTo(1);

            // Now repeat calls: both must hit their respective domain
            float[] hitAcademic = client.generateEmbedding(text, DomainScope.ACADEMICS);
            float[] hitPersonal = client.generateEmbedding(text, DomainScope.PERSONAL);

            assertThat(hitAcademic).isEqualTo(academicEmb);
            assertThat(hitPersonal).isEqualTo(personalEmb);
            assertThat(cache.getStats().hits()).isEqualTo(3);
        }

        @Test
        @DisplayName("ADV-03: Selective domain invalidation leaves other domains completely unaffected")
        void testSelectiveInvalidationIntegrity() {
            EmbeddingCache cache = new CaffeineEmbeddingCache(3000);

            int countPerDomain = 300;
            for (int i = 0; i < countPerDomain; i++) {
                cache.put(DomainScope.ACADEMICS, "item-" + i, new float[]{(float) i});
                cache.put(DomainScope.PERSONAL, "item-" + i, new float[]{(float) (i + 1000)});
                cache.put(DomainScope.CAREER, "item-" + i, new float[]{(float) (i + 2000)});
            }

            assertThat(cache.size()).isEqualTo(900);

            // Invalidate PERSONAL domain only
            cache.invalidateDomain(DomainScope.PERSONAL);

            assertThat(cache.size()).isEqualTo(600);

            for (int i = 0; i < countPerDomain; i++) {
                assertThat(cache.get(DomainScope.PERSONAL, "item-" + i))
                        .as("All PERSONAL items must be purged")
                        .isEmpty();

                assertThat(cache.get(DomainScope.ACADEMICS, "item-" + i))
                        .as("ACADEMICS items must remain intact")
                        .isPresent();

                assertThat(cache.get(DomainScope.CAREER, "item-" + i))
                        .as("CAREER items must remain intact")
                        .isPresent();
            }
        }
    }

    // =========================================================================
    // 2. FAIL-CLOSED VALIDATION & ZERO MUTATION ON FAILURE
    // =========================================================================
    @Nested
    @DisplayName("2. Fail-Closed Validation Verification")
    class FailClosedValidationVerification {

        @Test
        @DisplayName("ADV-04: Null domain throws UnscopedQueryException without accessing or mutating cache")
        void testFailClosedNullDomainDoesNotMutateCache() {
            EmbeddingCache cache = new CaffeineEmbeddingCache(100);

            // 1. Calling get(null, text)
            assertThatThrownBy(() -> cache.get(null, "some text"))
                    .isInstanceOf(UnscopedQueryException.class)
                    .hasMessageContaining("fail-closed");

            // Verify cache stats were untouched (0 hits, 0 misses, 0 size)
            EmbeddingCache.CacheStats statsAfterGet = cache.getStats();
            assertThat(statsAfterGet.hits()).isEqualTo(0);
            assertThat(statsAfterGet.misses()).isEqualTo(0);
            assertThat(statsAfterGet.size()).isEqualTo(0);

            // 2. Calling put(null, text, embedding)
            assertThatThrownBy(() -> cache.put(null, "some text", new float[]{1.0f}))
                    .isInstanceOf(UnscopedQueryException.class)
                    .hasMessageContaining("fail-closed");

            assertThat(cache.size()).isEqualTo(0);

            // 3. Calling invalidateDomain(null)
            assertThatThrownBy(() -> cache.invalidateDomain(null))
                    .isInstanceOf(UnscopedQueryException.class)
                    .hasMessageContaining("fail-closed");

            // 4. Instantiating EmbeddingCacheKey with null domain
            assertThatThrownBy(() -> new EmbeddingCacheKey(null, "valid text"))
                    .isInstanceOf(UnscopedQueryException.class)
                    .hasMessageContaining("fail-closed");

            assertThatThrownBy(() -> EmbeddingCacheKey.of(null, "valid text"))
                    .isInstanceOf(UnscopedQueryException.class)
                    .hasMessageContaining("fail-closed");
        }

        @Test
        @DisplayName("ADV-05: OllamaClientService fail-closed on null domain throws before computing or caching")
        void testOllamaClientServiceFailClosed() {
            EmbeddingCache cache = new CaffeineEmbeddingCache(100);
            OllamaClientService client = new OllamaClientService(new ObjectMapper(), cache);

            assertThatThrownBy(() -> client.generateEmbedding("Valid text", null))
                    .isInstanceOf(UnscopedQueryException.class)
                    .hasMessageContaining("fail-closed");

            assertThatThrownBy(() -> client.invalidateCacheDomain(null))
                    .isInstanceOf(UnscopedQueryException.class)
                    .hasMessageContaining("fail-closed");

            assertThat(cache.size()).isEqualTo(0);
            assertThat(cache.getStats().misses()).isEqualTo(0);
            assertThat(cache.getStats().hits()).isEqualTo(0);
        }

        @Test
        @DisplayName("ADV-06: DomainScope parsing and resolution fails closed on invalid/null/empty tokens")
        void testDomainScopeParsingFailClosed() {
            // Null or blank string parsing
            assertThatThrownBy(() -> DomainScope.parse(null))
                    .isInstanceOf(UnscopedQueryException.class);
            assertThatThrownBy(() -> DomainScope.parse(""))
                    .isInstanceOf(UnscopedQueryException.class);
            assertThatThrownBy(() -> DomainScope.parse("   "))
                    .isInstanceOf(UnscopedQueryException.class);

            // Unrecognized domain string
            assertThatThrownBy(() -> DomainScope.parse("SECURITY"))
                    .isInstanceOf(InvalidDomainException.class);
            assertThatThrownBy(() -> DomainScope.parse("ADMIN"))
                    .isInstanceOf(InvalidDomainException.class);

            // Collection parsing
            assertThatThrownBy(() -> DomainScope.parseScopes(null))
                    .isInstanceOf(UnscopedQueryException.class);
            assertThatThrownBy(() -> DomainScope.parseScopes(Collections.emptyList()))
                    .isInstanceOf(UnscopedQueryException.class);
            assertThatThrownBy(() -> DomainScope.parseScopes(List.of("INVALID_SCOPE")))
                    .isInstanceOf(InvalidDomainException.class);

            // Validation methods
            assertThatThrownBy(() -> DomainScope.validateScope(null))
                    .isInstanceOf(UnscopedQueryException.class);
            assertThatThrownBy(() -> DomainScope.validateScopes(null))
                    .isInstanceOf(UnscopedQueryException.class);
            assertThatThrownBy(() -> DomainScope.validateScopes(Collections.emptyList()))
                    .isInstanceOf(UnscopedQueryException.class);
            assertThatThrownBy(() -> DomainScope.validateScopes(Collections.singletonList(null)))
                    .isInstanceOf(UnscopedQueryException.class);
        }
    }

    // =========================================================================
    // 3. BOUNDED CAPACITY & EVICTION BOUNDARIES (>10,000 ENTRIES)
    // =========================================================================
    @Nested
    @DisplayName("3. Bounded Capacity & Eviction Verification")
    class BoundedCapacityAndEvictionVerification {

        @Test
        @DisplayName("ADV-07: Inserting >10,000 distinct 768-dim entries caps at 10,000 without OutOfMemoryError")
        void test10000CapacityBoundaryAndMemoryStability() {
            int capacity = 10_000;
            int totalInserts = 15_000;
            EmbeddingCache cache = new CaffeineEmbeddingCache(capacity);

            Runtime runtime = Runtime.getRuntime();
            System.gc();
            long memoryBefore = runtime.totalMemory() - runtime.freeMemory();

            // Insert 15,000 distinct 768-dim entries
            for (int i = 0; i < totalInserts; i++) {
                float[] vector = new float[768];
                vector[0] = (float) i;
                vector[767] = (float) (i * 2);

                cache.put(DomainScope.ACADEMICS, "Large-Corpus-Text-Chunk-Key-" + i, vector);

                if (i % 2500 == 0) {
                    assertThat(cache.size())
                            .as("Intermediate cache size must never exceed configured maximum")
                            .isLessThanOrEqualTo(capacity);
                }
            }

            EmbeddingCache.CacheStats finalStats = cache.getStats();

            assertThat(finalStats.size())
                    .as("Final cache size must be capped at 10,000")
                    .isLessThanOrEqualTo(capacity);

            assertThat(finalStats.evictions())
                    .as("At least 5,000 entries must have been evicted")
                    .isGreaterThanOrEqualTo(totalInserts - capacity);

            // Verify older entries were evicted (e.g., keys 0..1000)
            int evictedColdCount = 0;
            for (int i = 0; i < 1000; i++) {
                if (cache.get(DomainScope.ACADEMICS, "Large-Corpus-Text-Chunk-Key-" + i).isEmpty()) {
                    evictedColdCount++;
                }
            }
            assertThat(evictedColdCount)
                    .as("Early inserted cold entries must have been evicted")
                    .isGreaterThan(800);

            // Verify entries currently in Caffeine's admission window (the last 100 items inserted: 14900..14999)
            // are 100% preserved
            int windowPresentCount = 0;
            for (int i = 14900; i < 15000; i++) {
                if (cache.get(DomainScope.ACADEMICS, "Large-Corpus-Text-Chunk-Key-" + i).isPresent()) {
                    windowPresentCount++;
                }
            }
            assertThat(windowPresentCount)
                    .as("Entries in the W-TinyLFU admission window (last 100) must be preserved")
                    .isEqualTo(100);

            System.gc();
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            System.gc();
            long memoryAfter = runtime.totalMemory() - runtime.freeMemory();
            long heapDeltaMb = (memoryAfter - memoryBefore) / (1024 * 1024);

            // 10,000 entries * ~3KB vector = ~30MB. In full-suite runs, allow up to 250MB for background JVM allocations.
            assertThat(heapDeltaMb)
                    .as("Heap growth must remain bounded for 10,000 vector entries")
                    .isLessThan(300L);
        }

        @Test
        @DisplayName("ADV-08: LRU frequency bias preserves hot entries during eviction pressure")
        void testLRUFrequencyPreservesHotEntries() {
            int capacity = 500;
            EmbeddingCache cache = new CaffeineEmbeddingCache(capacity);

            // Insert initial entries 0..499
            for (int i = 0; i < 500; i++) {
                cache.put(DomainScope.CAREER, "key-" + i, new float[]{(float) i});
            }

            // Repeatedly access "key-0" and "key-1" to make them hot
            for (int h = 0; h < 50; h++) {
                cache.get(DomainScope.CAREER, "key-0");
                cache.get(DomainScope.CAREER, "key-1");
            }

            // Push in 500 new entries to force heavy eviction of cold items
            for (int i = 500; i < 1000; i++) {
                cache.put(DomainScope.CAREER, "key-" + i, new float[]{(float) i});
                if (i % 20 == 0) {
                    // maintain hot access during insertion stream
                    cache.get(DomainScope.CAREER, "key-0");
                    cache.get(DomainScope.CAREER, "key-1");
                }
            }

            assertThat(cache.size()).isLessThanOrEqualTo(capacity);
            assertThat(cache.get(DomainScope.CAREER, "key-0"))
                    .as("Hot key-0 must survive eviction pressure")
                    .isPresent();
            assertThat(cache.get(DomainScope.CAREER, "key-1"))
                    .as("Hot key-1 must survive eviction pressure")
                    .isPresent();
        }
    }

    // =========================================================================
    // 4. MUTATION RESISTANCE & DEFENSIVE COPYING
    // =========================================================================
    @Nested
    @DisplayName("4. Mutation Resistance Verification")
    class MutationResistanceVerification {

        @Test
        @DisplayName("ADV-09: Tampering with array returned from get() does NOT alter subsequent get() calls")
        void testTamperingReturnedArrayDoesNotMutateCache() {
            EmbeddingCache cache = new CaffeineEmbeddingCache(100);
            float[] initial = new float[]{10.0f, 20.0f, 30.0f};
            cache.put(DomainScope.ACADEMICS, "ImmutableCourse", initial);

            // First get
            float[] retrieved1 = cache.get(DomainScope.ACADEMICS, "ImmutableCourse").orElseThrow();
            assertThat(retrieved1).isEqualTo(new float[]{10.0f, 20.0f, 30.0f});

            // Adversarial mutation: tamper all elements
            Arrays.fill(retrieved1, -9999.0f);

            // Second get
            float[] retrieved2 = cache.get(DomainScope.ACADEMICS, "ImmutableCourse").orElseThrow();
            assertThat(retrieved2)
                    .as("Cache value must not be corrupted by mutations to previous returned array")
                    .isEqualTo(new float[]{10.0f, 20.0f, 30.0f});
            assertThat(retrieved2[0]).isEqualTo(10.0f);
        }

        @Test
        @DisplayName("ADV-10: Tampering with array passed to put() does NOT alter cached value")
        void testTamperingPutArrayDoesNotMutateCache() {
            EmbeddingCache cache = new CaffeineEmbeddingCache(100);
            float[] source = new float[]{42.0f, 84.0f};

            cache.put(DomainScope.PERSONAL, "SecretHabit", source);

            // Mutate source array immediately after put
            source[0] = Float.NaN;
            source[1] = -1.0f;

            float[] retrieved = cache.get(DomainScope.PERSONAL, "SecretHabit").orElseThrow();
            assertThat(retrieved[0]).isEqualTo(42.0f);
            assertThat(retrieved[1]).isEqualTo(84.0f);
        }

        @Test
        @DisplayName("ADV-11: Mutating vector from OllamaClientService does not corrupt cache")
        void testOllamaClientServiceMutationResistance() {
            EmbeddingCache cache = new CaffeineEmbeddingCache(100);
            OllamaClientService client = new OllamaClientService(new ObjectMapper(), cache);

            String query = "Dynamic Programming Bellman-Ford";
            float[] vec1 = client.generateEmbedding(query, DomainScope.ACADEMICS);

            float originalFirstVal = vec1[0];
            // Mutate caller's returned array
            vec1[0] = 77777.7f;

            // Second call (cache hit)
            float[] vec2 = client.generateEmbedding(query, DomainScope.ACADEMICS);
            assertThat(vec2[0])
                    .as("Subsequent cache hit must return original uncorrupted value")
                    .isEqualTo(originalFirstVal)
                    .isNotEqualTo(77777.7f);
        }
    }

    // =========================================================================
    // 5. EDGE CASES, NORMALIZATION & CONCURRENT INVALIDATION
    // =========================================================================
    @Nested
    @DisplayName("5. Edge Cases & Concurrency Stress")
    class EdgeCasesAndConcurrencyStress {

        @Test
        @DisplayName("ADV-12: Extreme text lengths and complex unicode whitespace normalize without failure")
        void testExtremeTextNormalization() {
            EmbeddingCache cache = new CaffeineEmbeddingCache(100);

            // Construct 50,000 character text with chaotic whitespaces (\t, \n, \r, spaces)
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5000; i++) {
                sb.append("   term").append(i % 10).append(" \t\n\r   ");
            }
            String rawLong = sb.toString();

            float[] vec = new float[]{1.0f};
            cache.put(DomainScope.CAREER, rawLong, vec);

            // Query with different spacing layout of same terms
            String collapsed = rawLong.strip().replaceAll("\\s+", " ");
            Optional<float[]> hit = cache.get(DomainScope.CAREER, collapsed);

            assertThat(hit).isPresent().contains(vec);
        }

        @Test
        @DisplayName("ADV-13: Concurrent invalidation and concurrent puts/gets do not throw exceptions")
        void testConcurrentInvalidationUnderLoad() throws InterruptedException {
            EmbeddingCache cache = new CaffeineEmbeddingCache(1000);
            int threadCount = 32;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            CountDownLatch readyLatch = new CountDownLatch(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

            DomainScope[] domains = DomainScope.values();

            for (int t = 0; t < threadCount; t++) {
                final int tid = t;
                executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                        for (int i = 0; i < 300; i++) {
                            DomainScope d = domains[i % domains.length];
                            if (tid == 0) {
                                // Thread 0 periodically invalidates
                                cache.invalidateDomain(d);
                            } else if (tid == 1) {
                                // Thread 1 periodically reads stats
                                cache.getStats();
                            } else if (tid % 2 == 0) {
                                // Writer threads
                                cache.put(d, "key-" + (i % 50), new float[]{(float) tid, (float) i});
                            } else {
                                // Reader threads
                                cache.get(d, "key-" + (i % 50));
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

            boolean finished = doneLatch.await(15, TimeUnit.SECONDS);
            executor.shutdownNow();

            assertThat(finished).as("Concurrent threads must complete without deadlocks").isTrue();
            assertThat(errors).as("Concurrent invalidation and reads/writes must not throw exceptions").isEmpty();
        }

        @Test
        @DisplayName("ADV-14: Null and empty text handling in get and put")
        void testNullAndEmptyTextHandling() {
            EmbeddingCache cache = new CaffeineEmbeddingCache(100);

            // get with null text returns empty
            assertThat(cache.get(DomainScope.ACADEMICS, null)).isEmpty();

            // put with null text or null vector is safe no-op
            cache.put(DomainScope.ACADEMICS, null, new float[]{1.0f});
            cache.put(DomainScope.ACADEMICS, "valid", null);
            assertThat(cache.size()).isEqualTo(0);

            // blank text normalizes to ""
            cache.put(DomainScope.ACADEMICS, "   ", new float[]{9.9f});
            assertThat(cache.size()).isEqualTo(1);
            assertThat(cache.get(DomainScope.ACADEMICS, "")).isPresent();
            assertThat(cache.get(DomainScope.ACADEMICS, "\t\n  ")).isPresent();
        }
    }
}
