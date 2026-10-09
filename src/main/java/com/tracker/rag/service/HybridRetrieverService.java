package com.tracker.rag.service;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.service.ai.AiClient;
import com.tracker.rag.dto.Citation;
import com.tracker.rag.dto.HybridSearchResult;
import com.tracker.rag.repository.vector.ScoredChunk;
import com.tracker.rag.repository.vector.VectorChunk;
import com.tracker.rag.repository.vector.VectorStore;
import com.tracker.rag.service.retrieval.InvertedIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Production hybrid retrieval engine blending dense vector similarity search
 * and sparse inverted index BM25 search with Reciprocal Rank Fusion (RRF k=60)
 * scoped strictly by DomainScope.
 */
@Service
public class HybridRetrieverService {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrieverService.class);
    private static final int RRF_K = 60;

    private final VectorStore vectorStore;
    private final AiClient ollamaClientService;
    private final InvertedIndexService invertedIndexService;
    @Autowired(required = false)
    private com.tracker.rag.repository.DocumentRecordRepository documents;

    @Autowired
    public HybridRetrieverService(
            VectorStore vectorStore,
            AiClient ollamaClientService,
            @Autowired(required = false) InvertedIndexService invertedIndexService
    ) {
        this.vectorStore = vectorStore;
        this.ollamaClientService = ollamaClientService;
        this.invertedIndexService = invertedIndexService;
    }

    public HybridRetrieverService(VectorStore vectorStore, AiClient ollamaClientService) {
        this(vectorStore, ollamaClientService, null);
    }

    public HybridSearchResult search(String query, DomainScope domain, int topK) {
        DomainScope.validateScope(domain);
        if (query == null || query.trim().isEmpty()) {
            return new HybridSearchResult("", domain, Collections.emptyList());
        }
        int k = Math.max(1, topK);
        int candidateLimit = Math.max(20, k * 3);
        if (documents != null && !"legacy".equals(ollamaClientService.embeddingSpace()) &&
                documents.findByDomain(domain).stream().anyMatch(doc -> doc.getChunkCount() > 0 &&
                        !ollamaClientService.embeddingSpace().equals(doc.getEmbeddingModelId()))) {
            throw new com.tracker.rag.service.ai.AiServiceException(409,
                    "The embedding model changed. Upload this area's indexed documents again before asking questions.");
        }

        // 1. Dense Vector Search via provider query embedding (domain-scoped cache)
        float[] queryVec = ollamaClientService.generateQueryEmbedding(query, domain);
        List<ScoredChunk> denseResults = 
                vectorStore.similaritySearch(queryVec, domain, candidateLimit);

        // 2. Sparse Inverted Index BM25 Search (independent corpus lookup)
        List<InvertedIndexService.ScoredPosting> sparseResults;
        if (invertedIndexService != null) {
            sparseResults = invertedIndexService.search(query, domain, candidateLimit);
        } else {
            sparseResults = performFallbackSparseSearch(query, denseResults, candidateLimit);
        }

        if ((denseResults == null || denseResults.isEmpty()) && (sparseResults == null || sparseResults.isEmpty())) {
            return new HybridSearchResult(query, domain, Collections.emptyList());
        }

        // 3. Reciprocal Rank Fusion (RRF k=60) with full provenance metadata
        Map<UUID, Double> rrfScores = new HashMap<>();
        Map<UUID, Integer> denseRanks = new HashMap<>();
        Map<UUID, Integer> sparseRanks = new HashMap<>();
        Map<UUID, CandidateMetadata> metadataMap = new HashMap<>();

        if (denseResults != null) {
            for (int i = 0; i < denseResults.size(); i++) {
                ScoredChunk sc = denseResults.get(i);
                if (sc == null || sc.chunk() == null) continue;
                UUID id = sc.chunk().id();
                int rank = i + 1;
                denseRanks.put(id, rank);
                rrfScores.merge(id, 1.0 / (RRF_K + rank), Double::sum);
                metadataMap.putIfAbsent(id, new CandidateMetadata(
                        id,
                        sc.chunk().documentId(),
                        sc.chunk().pageNumber(),
                        sc.chunk().headingPath(),
                        sc.chunk().content()
                ));
            }
        }

        if (sparseResults != null) {
            for (int i = 0; i < sparseResults.size(); i++) {
                InvertedIndexService.ScoredPosting sp = sparseResults.get(i);
                if (sp == null || sp.chunkId() == null) continue;
                UUID id = sp.chunkId();
                int rank = i + 1;
                sparseRanks.put(id, rank);
                rrfScores.merge(id, 1.0 / (RRF_K + rank), Double::sum);
                metadataMap.putIfAbsent(id, new CandidateMetadata(
                        id,
                        sp.documentId(),
                        sp.pageNumber(),
                        sp.headingPath(),
                        sp.content()
                ));
            }
        }

        if (rrfScores.isEmpty()) {
            return new HybridSearchResult(query, domain, Collections.emptyList());
        }

        // 4. Assemble Top-K Citations with Provenance and Deterministic Tie-Breaking
        Comparator<Map.Entry<UUID, Double>> comparator = Map.Entry.<UUID, Double>comparingByValue().reversed()
                .thenComparing(entry -> {
                    int d = denseRanks.getOrDefault(entry.getKey(), Integer.MAX_VALUE);
                    int s = sparseRanks.getOrDefault(entry.getKey(), Integer.MAX_VALUE);
                    return Math.min(d, s);
                })
                .thenComparing(entry -> entry.getKey().toString());

        List<Citation> citations = rrfScores.entrySet().stream()
                .sorted(comparator)
                .limit(k)
                .map(entry -> {
                    UUID chunkId = entry.getKey();
                    CandidateMetadata meta = metadataMap.get(chunkId);
                    double score = entry.getValue();
                    int dRank = denseRanks.getOrDefault(chunkId, 0);
                    int sRank = sparseRanks.getOrDefault(chunkId, 0);

                    return new Citation(
                            meta.documentId(),
                            meta.pageNumber() > 0 ? meta.pageNumber() : null,
                            meta.headingPath(),
                            meta.content(),
                            score,
                            dRank,
                            sRank
                    );
                })
                .toList();

        log.debug("[HybridRetriever] Found {} citations for query '{}' in domain {}", citations.size(), query, domain);
        return new HybridSearchResult(query, domain, citations);
    }

    private List<InvertedIndexService.ScoredPosting> performFallbackSparseSearch(
            String query, List<ScoredChunk> denseChunks, int limit
    ) {
        if (denseChunks == null || denseChunks.isEmpty()) {
            return Collections.emptyList();
        }
        String[] queryTerms = query.toLowerCase().split("\\s+");
        List<InvertedIndexService.ScoredPosting> scoredList = new ArrayList<>();

        for (ScoredChunk sc : denseChunks) {
            VectorChunk chunk = sc.chunk();
            String lowerContent = chunk.content().toLowerCase();
            double matchCount = 0;
            for (String term : queryTerms) {
                if (term.length() > 1 && lowerContent.contains(term)) {
                    matchCount += 1.0;
                }
            }
            if (matchCount > 0) {
                double bm25Score = matchCount / (1.0 + (chunk.content().length() / 200.0));
                scoredList.add(new InvertedIndexService.ScoredPosting(
                        chunk.id(),
                        chunk.documentId(),
                        chunk.pageNumber(),
                        chunk.headingPath(),
                        chunk.content(),
                        bm25Score
                ));
            }
        }

        scoredList.sort(Comparator.comparingDouble(InvertedIndexService.ScoredPosting::bm25Score).reversed());
        return scoredList.stream().limit(limit).toList();
    }

    private record CandidateMetadata(
            UUID chunkId,
            String documentId,
            int pageNumber,
            String headingPath,
            String content
    ) {}
}
