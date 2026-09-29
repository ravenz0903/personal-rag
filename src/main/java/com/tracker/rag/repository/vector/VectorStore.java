package com.tracker.rag.repository.vector;

import com.tracker.rag.domain.DomainScope;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Storage and similarity search abstraction for vector chunks.
 * Implementations must enforce strict fail-closed domain scoping.
 */
public interface VectorStore {

    /**
     * Persists or updates a single vector chunk.
     *
     * @param chunk the vector chunk to store (non-null)
     */
    void save(VectorChunk chunk);

    /**
     * Persists or updates a batch of vector chunks in a single atomic operation.
     *
     * @param chunks the list of vector chunks (non-null)
     */
    void saveAll(List<VectorChunk> chunks);

    /**
     * Deletes all vector chunks associated with the specified document identifier.
     * Used for transactional document re-ingestion.
     *
     * @param documentId the document identifier (non-null)
     */
    void deleteByDocumentId(String documentId);

    /**
     * Deletes a specific chunk by its unique ID.
     *
     * @param id the chunk UUID (non-null)
     */
    void deleteById(UUID id);

    /**
     * Performs a dense vector similarity search scoped to a single domain.
     *
     * @param queryEmbedding 768-dimensional float embedding vector of the query
     * @param scope mandatory domain scope (fails closed if null)
     * @param topK maximum number of results to return (must be > 0)
     * @return top-K ranked chunks sorted by similarity score descending
     */
    List<ScoredChunk> similaritySearch(float[] queryEmbedding, DomainScope scope, int topK);

    /**
     * Performs a dense vector similarity search scoped to multiple domains.
     *
     * @param queryEmbedding 768-dimensional float embedding vector of the query
     * @param scopes mandatory set of domain scopes (fails closed if null or empty)
     * @param topK maximum number of results to return (must be > 0)
     * @return top-K ranked chunks sorted by similarity score descending
     */
    List<ScoredChunk> similaritySearch(float[] queryEmbedding, Set<DomainScope> scopes, int topK);

    /**
     * Returns the total count of indexed chunks.
     */
    long count();

    /**
     * Returns the total count of indexed chunks for a given domain scope.
     */
    long countByDomain(DomainScope scope);
}
