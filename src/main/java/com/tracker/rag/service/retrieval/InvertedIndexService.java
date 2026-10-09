package com.tracker.rag.service.retrieval;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.repository.vector.VectorChunk;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * In-memory domain-partitioned inverted index contract for sparse BM25 retrieval.
 */
public interface InvertedIndexService {

    /**
     * Scored candidate posting returned from sparse BM25 search.
     */
    record ScoredPosting(
            UUID chunkId,
            String documentId,
            int pageNumber,
            String headingPath,
            String content,
            double bm25Score
    ) {}

    /**
     * Indexes a single vector chunk into its domain-isolated postings list.
     *
     * @param chunk vector chunk to index (non-null)
     */
    void indexChunk(VectorChunk chunk);

    /**
     * Indexes a batch of vector chunks into their respective domain-isolated postings lists.
     *
     * @param chunks collection of vector chunks
     */
    void indexChunks(Collection<VectorChunk> chunks);

    /**
     * Removes all postings associated with the given documentId in the specified domain.
     * Used during transactional document re-ingestion and rollback.
     *
     * @param documentId document identifier
     * @param domain domain scope (fail-closed if null)
     */
    void removeDocument(String documentId, DomainScope domain);

    /**
     * Removes a specific chunk from the inverted index.
     *
     * @param chunkId unique chunk identifier
     * @param domain domain scope (fail-closed if null)
     */
    void removeChunk(UUID chunkId, DomainScope domain);

    /**
     * Performs an Okapi BM25 sparse search strictly within the specified domain.
     *
     * @param query search query
     * @param domain domain scope (fail-closed if null)
     * @param topK maximum candidate count to return
     * @return top-K ranked scored postings sorted by BM25 score descending
     */
    List<ScoredPosting> search(String query, DomainScope domain, int topK);

    /**
     * Returns the total indexed chunk count (N) for the specified domain.
     *
     * @param domain domain scope (fail-closed if null)
     * @return count of indexed chunks in domain
     */
    int size(DomainScope domain);

    /**
     * Clears all postings, statistics, and mappings across all domains.
     */
    void clear();
}
