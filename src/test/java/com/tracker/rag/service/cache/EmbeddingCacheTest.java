package com.tracker.rag.service.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.domain.UnscopedQueryException;
import com.tracker.rag.service.OllamaClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EmbeddingCache Unit & Contract Tests")
public class EmbeddingCacheTest {

    private EmbeddingCache cache;

    @BeforeEach
    void setUp() {
        cache = new CaffeineEmbeddingCache(100);
    }

    @Nested
    @DisplayName("Basic Put, Get & Hit/Miss Operations")
    class HitMissOperations {

        @Test
        @DisplayName("TC-CACHE-01: Put and get returns matching embedding and records cache hit")
        void testPutAndGetHit() {
            float[] vector = new float[]{0.1f, 0.2f, 0.3f};
            cache.put(DomainScope.ACADEMICS, "Quantum physics", vector);

            Optional<float[]> result = cache.get(DomainScope.ACADEMICS, "Quantum physics");
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(vector);

            EmbeddingCache.CacheStats stats = cache.getStats();
            assertThat(stats.hits()).isEqualTo(1);
            assertThat(stats.misses()).isEqualTo(0);
            assertThat(stats.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("TC-CACHE-02: Get on uncached text returns empty and records cache miss")
        void testGetMiss() {
            Optional<float[]> result = cache.get(DomainScope.ACADEMICS, "Uncached Topic");
            assertThat(result).isEmpty();

            EmbeddingCache.CacheStats stats = cache.getStats();
            assertThat(stats.hits()).isEqualTo(0);
            assertThat(stats.misses()).isEqualTo(1);
        }

        @Test
        @DisplayName("TC-CACHE-03: Put with existing key overwrites value without increasing size")
        void testOverwriteExisting() {
            float[] v1 = new float[]{0.1f};
            float[] v2 = new float[]{0.9f};

            cache.put(DomainScope.ACADEMICS, "Topic", v1);
            cache.put(DomainScope.ACADEMICS, "Topic", v2);

            Optional<float[]> result = cache.get(DomainScope.ACADEMICS, "Topic");
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(v2);
            assertThat(cache.getStats().size()).isEqualTo(1);
        }

        @Test
        @DisplayName("TC-CACHE-04: Text normalization collapses whitespace and trims")
        void testTextNormalizationTrimming() {
            float[] v = new float[]{0.5f};
            cache.put(DomainScope.CAREER, "software engineer resume", v);

            Optional<float[]> hit = cache.get(DomainScope.CAREER, "  software   \n\t engineer   resume  ");
            assertThat(hit).isPresent();
            assertThat(hit.get()).isEqualTo(v);
        }

        @Test
        @DisplayName("TC-CACHE-05: Defensive copying prevents array corruption by caller mutations")
        void testDefensiveCopying() {
            float[] original = new float[]{1.0f, 2.0f, 3.0f};
            cache.put(DomainScope.ACADEMICS, "ImmutableTest", original);

            // Mutate original array after put
            original[0] = 999.0f;

            float[] retrieved = cache.get(DomainScope.ACADEMICS, "ImmutableTest").orElseThrow();
            assertThat(retrieved[0]).as("Cache internal state must not be modified by external mutations").isEqualTo(1.0f);

            // Mutate retrieved array after get
            retrieved[1] = 888.0f;
            float[] retrievedAgain = cache.get(DomainScope.ACADEMICS, "ImmutableTest").orElseThrow();
            assertThat(retrievedAgain[1]).as("Second get must not reflect previous retrieved mutation").isEqualTo(2.0f);
        }
    }

    @Nested
    @DisplayName("Bounded Eviction & Capacity Limit")
    class EvictionPolicy {

        @Test
        @DisplayName("TC-CACHE-06: Bounded eviction maintains capacity bound when inserting excess items")
        void testBoundedEvictionAtCapacity() {
            int maxCap = 10;
            EmbeddingCache tinyCache = new CaffeineEmbeddingCache(maxCap);

            for (int i = 0; i < 25; i++) {
                tinyCache.put(DomainScope.ACADEMICS, "Key-" + i, new float[]{(float) i});
            }

            assertThat(tinyCache.getStats().size())
                    .as("Size must never exceed capacity")
                    .isLessThanOrEqualTo(maxCap);
            assertThat(tinyCache.getStats().evictions())
                    .as("Must have recorded evictions")
                    .isGreaterThanOrEqualTo(15);
        }
    }

    @Nested
    @DisplayName("Domain Scope Isolation & Invalidation")
    class DomainIsolation {

        @Test
        @DisplayName("TC-CACHE-07: Identical query text across different domains is strictly isolated")
        void testCrossDomainIsolation() {
            float[] personalVector = new float[]{0.77f};
            cache.put(DomainScope.PERSONAL, "health records", personalVector);

            assertThat(cache.get(DomainScope.ACADEMICS, "health records")).as("Academic query must miss").isEmpty();
            assertThat(cache.get(DomainScope.CAREER, "health records")).as("Career query must miss").isEmpty();
            assertThat(cache.get(DomainScope.PERSONAL, "health records")).isPresent();
        }

        @Test
        @DisplayName("TC-CACHE-08: Fail-closed on null domain scope for get, put, and invalidate")
        void testFailClosedOnNullDomain() {
            assertThatThrownBy(() -> cache.get(null, "query"))
                    .isInstanceOf(UnscopedQueryException.class)
                    .hasMessageContaining("fail-closed");

            assertThatThrownBy(() -> cache.put(null, "query", new float[]{1.0f}))
                    .isInstanceOf(UnscopedQueryException.class)
                    .hasMessageContaining("fail-closed");

            assertThatThrownBy(() -> cache.invalidateDomain(null))
                    .isInstanceOf(UnscopedQueryException.class)
                    .hasMessageContaining("fail-closed");

            assertThatThrownBy(() -> new EmbeddingCacheKey(null, "query"))
                    .isInstanceOf(UnscopedQueryException.class)
                    .hasMessageContaining("fail-closed");

            assertThatThrownBy(() -> new EmbeddingCacheKey(DomainScope.ACADEMICS, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("fail-closed");
        }

        @Test
        @DisplayName("TC-CACHE-09: Invalidate domain purges target domain while preserving other domains")
        void testSelectiveInvalidateDomain() {
            cache.put(DomainScope.ACADEMICS, "syllabus", new float[]{1.0f});
            cache.put(DomainScope.PERSONAL, "diary", new float[]{2.0f});
            cache.put(DomainScope.CAREER, "interview", new float[]{3.0f});

            cache.invalidateDomain(DomainScope.ACADEMICS);

            assertThat(cache.get(DomainScope.ACADEMICS, "syllabus")).isEmpty();
            assertThat(cache.get(DomainScope.PERSONAL, "diary")).isPresent();
            assertThat(cache.get(DomainScope.CAREER, "interview")).isPresent();
        }

        @Test
        @DisplayName("TC-CACHE-10: Clear purges all cached entries across all domains")
        void testClearAll() {
            cache.put(DomainScope.ACADEMICS, "syllabus", new float[]{1.0f});
            cache.put(DomainScope.PERSONAL, "diary", new float[]{2.0f});

            cache.clear();

            assertThat(cache.getStats().size()).isEqualTo(0);
            assertThat(cache.get(DomainScope.ACADEMICS, "syllabus")).isEmpty();
            assertThat(cache.get(DomainScope.PERSONAL, "diary")).isEmpty();
        }
    }

    @Nested
    @DisplayName("OllamaClientService Cache Integration")
    class OllamaIntegration {

        @Test
        @DisplayName("TC-CACHE-11: Miss-then-hit cycle in OllamaClientService with sub-10ms cached retrieval")
        void testOllamaClientServiceIntegration() {
            OllamaClientService client = new OllamaClientService(new ObjectMapper(), cache);

            String query = "Explain Graph Search and Dijkstra";

            // First call: Miss, computes and stores
            float[] emb1 = client.generateEmbedding(query, DomainScope.ACADEMICS);
            assertThat(emb1).isNotNull().hasSize(768);
            assertThat(cache.getStats().misses()).isEqualTo(1);
            assertThat(cache.getStats().hits()).isEqualTo(0);

            // Second call: Hit, returns cached vector
            long start = System.nanoTime();
            float[] emb2 = client.generateEmbedding(query, DomainScope.ACADEMICS);
            long elapsedNanos = System.nanoTime() - start;
            double elapsedMs = elapsedNanos / 1_000_000.0;

            assertThat(emb2).isEqualTo(emb1);
            assertThat(cache.getStats().hits()).isEqualTo(1);
            assertThat(elapsedMs).as("Cached embedding must resolve in <10ms").isLessThan(10.0);
        }

        @Test
        @DisplayName("TC-CACHE-12: Unscoped generateEmbedding computes without polluting cache")
        void testUnscopedDoesNotCache() {
            OllamaClientService client = new OllamaClientService(new ObjectMapper(), cache);

            float[] emb = client.generateEmbedding("Unscoped query");
            assertThat(emb).isNotNull().hasSize(768);
            assertThat(cache.getStats().size()).isEqualTo(0);
            assertThat(cache.getStats().hits()).isEqualTo(0);
            assertThat(cache.getStats().misses()).isEqualTo(0);
        }

        @Test
        @DisplayName("TC-CACHE-13: Backward-compatible constructor without cache runs normally")
        void testBackwardCompatibleConstructor() {
            OllamaClientService client = new OllamaClientService(new ObjectMapper());
            float[] emb = client.generateEmbedding("Legacy test", DomainScope.ACADEMICS);
            assertThat(emb).isNotNull().hasSize(768);
        }

        @Test
        @DisplayName("TC-CACHE-14: Invalidation delegators on OllamaClientService operate correctly")
        void testOllamaClientInvalidationDelegators() {
            OllamaClientService client = new OllamaClientService(new ObjectMapper(), cache);

            client.generateEmbedding("Topic A", DomainScope.ACADEMICS);
            client.generateEmbedding("Topic B", DomainScope.CAREER);
            assertThat(client.getCacheStats().orElseThrow().size()).isEqualTo(2);

            client.invalidateCacheDomain(DomainScope.ACADEMICS);
            assertThat(cache.get(DomainScope.ACADEMICS, "Topic A")).isEmpty();
            assertThat(cache.get(DomainScope.CAREER, "Topic B")).isPresent();

            client.clearCache();
            assertThat(client.getCacheStats().orElseThrow().size()).isEqualTo(0);
        }
    }
}
