package com.tracker.rag.service.cache;

import com.tracker.rag.domain.DomainScope;

/**
 * Immutable, domain-isolated cache key for vector embeddings.
 * Enforces strict fail-closed scoping and canonical text normalization.
 */
public record EmbeddingCacheKey(DomainScope domain, String text) {

    public EmbeddingCacheKey {
        DomainScope.validateScope(domain);
        if (text == null) {
            throw new IllegalArgumentException("Cache key text cannot be null (fail-closed)");
        }
        text = normalize(text);
    }

    /**
     * Factory method for creating a validated, normalized cache key.
     */
    public static EmbeddingCacheKey of(DomainScope domain, String text) {
        return new EmbeddingCacheKey(domain, text);
    }

    /**
     * Canonical text normalization:
     * 1. Strips leading and trailing whitespace.
     * 2. Collapses consecutive whitespace (spaces, tabs, newlines) into a single space.
     * 3. Preserves character casing to preserve embedding model token fidelity.
     */
    public static String normalize(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        return input.strip().replaceAll("\\s+", " ");
    }
}
