package com.tracker.rag.e2e.stub;

import com.tracker.rag.e2e.contract.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Hybrid retrieval engine blending dense vector and sparse keyword search
 * with Reciprocal Rank Fusion (RRF k=60) per survey_report.md § 1.2 and PROJECT.md F24-F27.
 */
public class DeterministicHybridRetrieverStub implements HybridRetriever {

    private static final int RRF_K = 60;
    private final VectorStore vectorStore;
    private final List<VectorChunk> chunkIndex;

    public DeterministicHybridRetrieverStub(VectorStore vectorStore, List<VectorChunk> chunkIndex) {
        this.vectorStore = vectorStore;
        this.chunkIndex = chunkIndex != null ? chunkIndex : Collections.emptyList();
    }

    @Override
    public HybridSearchResult search(String query, DomainScope domain, int topK) {
        DomainScope.validateScope(domain);
        if (query == null || query.trim().isEmpty()) {
            return new HybridSearchResult("", domain, Collections.emptyList());
        }
        int k = Math.max(1, topK);

        // 1. Filter candidates strictly by domain scope (isolation enforcement)
        List<VectorChunk> domainChunks = chunkIndex.stream()
                .filter(c -> c.domain() == domain)
                .toList();

        if (domainChunks.isEmpty()) {
            return new HybridSearchResult(query, domain, Collections.emptyList());
        }

        // 2. Dense Vector Ranking (via query embedding simulation)
        float[] queryVec = LoopbackEnforcingClientStub.generateDeterministicEmbedding(query);
        List<ScoredChunk> denseResults = vectorStore.similaritySearch(queryVec, domain, 20);
        List<UUID> denseRankedIds = denseResults.stream()
                .map(sc -> sc.chunk().id())
                .toList();

        // 3. Sparse Keyword Ranking (BM25 simulation)
        List<ScoredChunk> sparseResults = performSparseKeywordSearch(query, domainChunks, 20);
        List<UUID> sparseRankedIds = sparseResults.stream()
                .map(sc -> sc.chunk().id())
                .toList();

        // 4. Reciprocal Rank Fusion (RRF k=60)
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

        // Lookup map for fast chunk resolution
        Map<UUID, VectorChunk> lookup = domainChunks.stream()
                .collect(Collectors.toMap(VectorChunk::id, c -> c, (a, b) -> a));

        // 5. Assemble Top-K Citations with Provenance
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

        return new HybridSearchResult(query, domain, citations);
    }

    private List<ScoredChunk> performSparseKeywordSearch(String query, List<VectorChunk> chunks, int limit) {
        String[] queryTerms = query.toLowerCase().split("\\s+");
        List<ScoredChunk> scoredList = new ArrayList<>();

        for (VectorChunk chunk : chunks) {
            String lowerContent = chunk.content().toLowerCase();
            double matchCount = 0;
            for (String term : queryTerms) {
                if (lowerContent.contains(term)) {
                    matchCount += 1.0;
                }
            }
            if (matchCount > 0) {
                // BM25-proportional scoring
                double bm25Score = matchCount / (1.0 + (chunk.content().length() / 200.0));
                scoredList.add(new ScoredChunk(chunk, bm25Score));
            }
        }

        scoredList.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return scoredList.stream().limit(limit).toList();
    }
}
