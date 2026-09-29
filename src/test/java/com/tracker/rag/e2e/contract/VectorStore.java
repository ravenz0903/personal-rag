package com.tracker.rag.e2e.contract;

import java.util.List;

public interface VectorStore {
    void save(VectorChunk chunk);

    void saveAll(List<VectorChunk> chunks);

    void deleteByDocumentId(String documentId);

    List<ScoredChunk> similaritySearch(float[] queryEmbedding, DomainScope scope, int topK);

    int countChunks(DomainScope scope);
}
