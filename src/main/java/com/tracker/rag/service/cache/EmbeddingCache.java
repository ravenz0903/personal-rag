package com.tracker.rag.service.cache;

import com.tracker.rag.domain.DomainScope;

import java.util.Optional;

/**
 * Thread-safe, bounded cache for 768-dimensional vector embeddings.
 */
public interface EmbeddingCache {

    record CacheKey(DomainScope domain, String text) {}

    record CacheStats(long hits, long misses, long evictions, int size, int capacity) {
        public CacheStats(long hits, long misses, int size, int capacity) {
            this(hits, misses, 0L, size, capacity);
        }

        public double hitRate() {
            long total = hits + misses;
            return total == 0 ? 0.0 : (double) hits / total;
        }
    }

    /**
     * Retrieve cached embedding. Fails closed with UnscopedQueryException if domain is null.
     */
    Optional<float[]> get(DomainScope domain, String text);

    /**
     * Store embedding in cache. Fails closed with UnscopedQueryException if domain is null.
     */
    void put(DomainScope domain, String text, float[] embedding);

    /**
     * Invalidate all cached entries for the specified domain.
     */
    void invalidateDomain(DomainScope domain);

    /**
     * Clear all cached entries across all domains.
     */
    void clear();

    /**
     * Return current snapshot of cache metrics.
     */
    CacheStats getStats();

    /**
     * Current number of entries in cache.
     */
    default int size() {
        return getStats().size();
    }

    /**
     * Configured maximum entry capacity.
     */
    default int capacity() {
        return getStats().capacity();
    }
}
