package com.tracker.rag.service.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tracker.rag.domain.DomainScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * High-performance, thread-safe bounded embedding cache backed by Caffeine (Window TinyLFU).
 * Enforces fail-closed domain isolation, defensive array cloning, and metrics tracking.
 */
@Component
public class CaffeineEmbeddingCache implements EmbeddingCache {

    private static final Logger log = LoggerFactory.getLogger(CaffeineEmbeddingCache.class);
    public static final int DEFAULT_MAX_CAPACITY = 10_000;

    private final int maxCapacity;
    private final Cache<EmbeddingCacheKey, float[]> cache;

    public CaffeineEmbeddingCache() {
        this(DEFAULT_MAX_CAPACITY);
    }

    public CaffeineEmbeddingCache(int maxCapacity) {
        this.maxCapacity = maxCapacity;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxCapacity)
                .executor(Runnable::run)
                .recordStats()
                .build();
    }

    @Override
    public Optional<float[]> get(DomainScope domain, String text) {
        DomainScope.validateScope(domain);
        if (text == null) {
            return Optional.empty();
        }

        EmbeddingCacheKey key = EmbeddingCacheKey.of(domain, text);
        float[] vector = cache.getIfPresent(key);
        if (vector != null) {
            return Optional.of(vector.clone()); // Defensive copy
        }
        return Optional.empty();
    }

    @Override
    public void put(DomainScope domain, String text, float[] embedding) {
        DomainScope.validateScope(domain);
        if (text == null || embedding == null) {
            return;
        }

        EmbeddingCacheKey key = EmbeddingCacheKey.of(domain, text);
        cache.put(key, embedding.clone()); // Defensive copy
    }

    @Override
    public void invalidateDomain(DomainScope domain) {
        DomainScope.validateScope(domain);
        cache.asMap().keySet().removeIf(k -> k.domain() == domain);
        cache.cleanUp();
        log.debug("[EmbeddingCache] Invalidated all cache entries for domain {}", domain);
    }

    @Override
    public void clear() {
        cache.invalidateAll();
        cache.cleanUp();
    }

    @Override
    public CacheStats getStats() {
        cache.cleanUp();
        var stats = cache.stats();
        return new CacheStats(
                stats.hitCount(),
                stats.missCount(),
                stats.evictionCount(),
                (int) cache.estimatedSize(),
                maxCapacity
        );
    }

    @Override
    public int size() {
        cache.cleanUp();
        return (int) cache.estimatedSize();
    }

    @Override
    public int capacity() {
        return maxCapacity;
    }
}
