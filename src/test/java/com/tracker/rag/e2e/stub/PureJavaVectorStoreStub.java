package com.tracker.rag.e2e.stub;

import com.tracker.rag.e2e.contract.DomainScope;
import com.tracker.rag.e2e.contract.ScoredChunk;
import com.tracker.rag.e2e.contract.VectorChunk;
import com.tracker.rag.e2e.contract.VectorStore;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pure Java Vector Store with Cosine Similarity and Min-Heap Top-K selection
 * implementing embedded fallback per survey_report.md § 2.2.3 and PROJECT.md F24 & F30.
 */
public class PureJavaVectorStoreStub implements VectorStore {

    private final List<VectorChunk> chunkStore = new CopyOnWriteArrayList<>();

    @Override
    public void save(VectorChunk chunk) {
        Objects.requireNonNull(chunk, "chunk cannot be null");
        DomainScope.validateScope(chunk.domain());
        chunkStore.add(chunk);
    }

    @Override
    public void saveAll(List<VectorChunk> chunks) {
        if (chunks != null) {
            for (VectorChunk chunk : chunks) {
                save(chunk);
            }
        }
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        Objects.requireNonNull(documentId, "documentId cannot be null");
        chunkStore.removeIf(c -> documentId.equals(c.documentId()));
    }

    @Override
    public List<ScoredChunk> similaritySearch(float[] queryEmbedding, DomainScope scope, int topK) {
        DomainScope.validateScope(scope);
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            return Collections.emptyList();
        }
        int k = Math.max(1, topK);

        PriorityQueue<ScoredChunk> minHeap = new PriorityQueue<>(k, Comparator.comparingDouble(ScoredChunk::score));

        for (VectorChunk chunk : chunkStore) {
            // Strict Domain Scoping Filter
            if (chunk.domain() != scope) {
                continue;
            }

            float[] vec = chunk.embedding();
            double sim = (vec != null && vec.length == queryEmbedding.length)
                    ? computeCosineSimilarity(queryEmbedding, vec)
                    : 0.0;

            ScoredChunk scored = new ScoredChunk(chunk, sim);
            if (minHeap.size() < k) {
                minHeap.offer(scored);
            } else if (sim > minHeap.peek().score()) {
                minHeap.poll();
                minHeap.offer(scored);
            }
        }

        List<ScoredChunk> result = new ArrayList<>(minHeap);
        result.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return Collections.unmodifiableList(result);
    }

    @Override
    public int countChunks(DomainScope scope) {
        DomainScope.validateScope(scope);
        int count = 0;
        for (VectorChunk chunk : chunkStore) {
            if (chunk.domain() == scope) {
                count++;
            }
        }
        return count;
    }

    public static double computeCosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
