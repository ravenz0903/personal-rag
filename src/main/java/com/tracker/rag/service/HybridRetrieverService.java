package com.tracker.rag.service;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.dto.Citation;
import com.tracker.rag.dto.HybridSearchResult;
import com.tracker.rag.repository.vector.ScoredChunk;
import com.tracker.rag.repository.vector.VectorChunk;
import com.tracker.rag.repository.vector.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Production hybrid retrieval engine blending dense vector similarity search
 * and sparse keyword search with Reciprocal Rank Fusion (RRF k=60) scoped by DomainScope.
 */
@Service
public class HybridRetrieverService {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrieverService.class);
    private static final int RRF_K = 60;

    private final VectorStore vectorStore;
    private final OllamaClientService ollamaClientService;

    public HybridRetrieverService(VectorStore vectorStore, OllamaClientService ollamaClientService) {
        this.vectorStore = vectorStore;
        this.ollamaClientService = ollamaClientService;
    }

    public HybridSearchResult search(String query, DomainScope domain, int topK) {
        DomainScope.validateScope(domain);
        if (query == null || query.trim().isEmpty()) {
            return new HybridSearchResult("", domain, Collections.emptyList());
        }
        int k = Math.max(1, topK);

        // 1. Dense Vector Search via Ollama embedding
        float[] queryVec = ollamaClientService.generateEmbedding(query);
        List<ScoredChunk> denseResults = 
                vectorStore.similaritySearch(queryVec, domain, Math.max(20, k * 3));

        if (denseResults.isEmpty()) {
            return new HybridSearchResult(query, domain, Collections.emptyList());
        }

        List<UUID> denseRankedIds = denseResults.stream()
                .map(sc -> sc.chunk().id())
                .toList();

        Map<UUID, VectorChunk> lookup = new HashMap<>();
        for (var sc : denseResults) {
            lookup.put(sc.chunk().id(), sc.chunk());
        }

        // 2. Sparse Keyword Search over retrieved candidates
        List<ScoredCandidate> sparseResults = performSparseKeywordSearch(query, lookup.values(), 20);
        List<UUID> sparseRankedIds = sparseResults.stream()
                .map(sc -> sc.chunk.id())
                .toList();

        // 3. Reciprocal Rank Fusion (RRF k=60)
        Map<UUID, Double> rrfScores = new HashMap<>();
        Map<UUID, Integer> denseRanks = new HashMap<>();
        Map<UUID, Integer> sparseRanks = new HashMap<>();

        for (int rank = 0; rank < denseRankedIds.size(); rank++) {
            UUID id = denseRankedIds.get(rank);
            denseRanks.put(id, rank + 1);
            rrfScores.merge(id, 1.0 / (RRF_K + (rank + 1)), Double::sum);
        }

        for (int rank = 0; rank < sparseRankedIds.size(); rank++) {
            UUID id = sparseRankedIds.get(rank);
            sparseRanks.put(id, rank + 1);
            rrfScores.merge(id, 1.0 / (RRF_K + (rank + 1)), Double::sum);
        }

        // 4. Assemble Top-K Citations with Provenance
        List<Citation> citations = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(k)
                .map(entry -> {
                    UUID chunkId = entry.getKey();
                    VectorChunk chunk = lookup.get(chunkId);
                    double score = entry.getValue();
                    int dRank = denseRanks.getOrDefault(chunkId, 0);
                    int sRank = sparseRanks.getOrDefault(chunkId, 0);

                    return new Citation(
                            chunk.documentId(),
                            chunk.pageNumber() > 0 ? chunk.pageNumber() : null,
                            chunk.headingPath(),
                            chunk.content(),
                            score,
                            dRank,
                            sRank
                    );
                })
                .toList();

        log.debug("[HybridRetriever] Found {} citations for query '{}' in domain {}", citations.size(), query, domain);
        return new HybridSearchResult(query, domain, citations);
    }

    private List<ScoredCandidate> performSparseKeywordSearch(String query, Collection<VectorChunk> chunks, int limit) {
        String[] queryTerms = query.toLowerCase().split("\\s+");
        List<ScoredCandidate> scoredList = new ArrayList<>();

        for (VectorChunk chunk : chunks) {
            String lowerContent = chunk.content().toLowerCase();
            double matchCount = 0;
            for (String term : queryTerms) {
                if (term.length() > 2 && lowerContent.contains(term)) {
                    matchCount += 1.0;
                }
            }
            if (matchCount > 0) {
                double bm25Score = matchCount / (1.0 + (chunk.content().length() / 200.0));
                scoredList.add(new ScoredCandidate(chunk, bm25Score));
            }
        }

        scoredList.sort(Comparator.comparingDouble((ScoredCandidate c) -> c.score).reversed());
        return scoredList.stream().limit(limit).toList();
    }

    private record ScoredCandidate(VectorChunk chunk, double score) {}
}
