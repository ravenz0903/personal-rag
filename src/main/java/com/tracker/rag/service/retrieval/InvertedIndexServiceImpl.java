package com.tracker.rag.service.retrieval;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.repository.vector.VectorChunk;
import com.tracker.rag.repository.vector.VectorStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Production in-memory inverted index service with domain-partitioned postings lists,
 * ReentrantReadWriteLock concurrency, O(1) reverse lookup document invalidation,
 * and Okapi BM25 scoring with smoothed Robertson IDF.
 */
@Service
public class InvertedIndexServiceImpl implements InvertedIndexService {

    private static final Logger log = LoggerFactory.getLogger(InvertedIndexServiceImpl.class);

    private final VectorStore vectorStore;
    private final Map<DomainScope, DomainIndex> domainPartitions = new EnumMap<>(DomainScope.class);

    @Autowired
    public InvertedIndexServiceImpl(@Autowired(required = false) VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        for (DomainScope scope : DomainScope.values()) {
            domainPartitions.put(scope, new DomainIndex(scope));
        }
    }

    public InvertedIndexServiceImpl() {
        this(null);
    }

    @PostConstruct
    public void hydrateOnStartup() {
        if (vectorStore == null) {
            log.info("[InvertedIndex] Standalone mode (no VectorStore bean). In-memory index initialized empty.");
            return;
        }

        try {
            List<VectorChunk> chunks = vectorStore.findAll();
            if (chunks != null && !chunks.isEmpty()) {
                indexChunks(chunks);
                log.info("[InvertedIndex] Hydrated {} existing chunks from VectorStore into in-memory inverted index.", chunks.size());
            } else {
                log.info("[InvertedIndex] Persistent VectorStore is empty on startup. Index initialized empty.");
            }
        } catch (Exception e) {
            log.warn("[InvertedIndex] Startup hydration from VectorStore bypassed or failed gracefully: {}", e.getMessage());
        }
    }

    @Override
    public void indexChunk(VectorChunk chunk) {
        if (chunk == null) return;
        DomainScope domain = DomainScope.validateScope(chunk.domain());
        domainPartitions.get(domain).indexChunk(chunk);
    }

    @Override
    public void indexChunks(Collection<VectorChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return;

        Map<DomainScope, List<VectorChunk>> byDomain = new EnumMap<>(DomainScope.class);
        for (VectorChunk chunk : chunks) {
            if (chunk != null) {
                DomainScope domain = DomainScope.validateScope(chunk.domain());
                byDomain.computeIfAbsent(domain, k -> new ArrayList<>()).add(chunk);
            }
        }

        for (Map.Entry<DomainScope, List<VectorChunk>> entry : byDomain.entrySet()) {
            domainPartitions.get(entry.getKey()).indexChunks(entry.getValue());
        }
    }

    @Override
    public void removeDocument(String documentId, DomainScope domain) {
        DomainScope.validateScope(domain);
        if (documentId == null) return;
        domainPartitions.get(domain).removeDocument(documentId);
    }

    @Override
    public void removeChunk(UUID chunkId, DomainScope domain) {
        DomainScope.validateScope(domain);
        if (chunkId == null) return;
        domainPartitions.get(domain).removeChunk(chunkId);
    }

    @Override
    public List<ScoredPosting> search(String query, DomainScope domain, int topK) {
        DomainScope.validateScope(domain);
        return domainPartitions.get(domain).search(query, topK);
    }

    @Override
    public int size(DomainScope domain) {
        DomainScope.validateScope(domain);
        return domainPartitions.get(domain).size();
    }

    @Override
    public void clear() {
        for (DomainScope scope : DomainScope.values()) {
            domainPartitions.get(scope).clear();
        }
    }

    // =========================================================================
    // Domain Index Inner Partition
    // =========================================================================
    private static class DomainIndex {
        private final DomainScope domain;
        private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock(false);

        // Internal structures strictly guarded by rwLock
        private final Map<String, List<Posting>> postingsByTerm = new HashMap<>();
        private final Map<String, Set<UUID>> docToChunks = new HashMap<>();
        private final Map<UUID, IndexedChunkMetadata> chunksById = new HashMap<>();

        private int totalChunks = 0;
        private long totalTerms = 0;
        private double avgdl = 0.0;

        private static final double K1 = 1.2;
        private static final double B = 0.75;
        private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-zA-Z0-9]+");

        DomainIndex(DomainScope domain) {
            this.domain = domain;
        }

        void indexChunk(VectorChunk chunk) {
            rwLock.writeLock().lock();
            try {
                indexChunkInternal(chunk);
            } finally {
                rwLock.writeLock().unlock();
            }
        }

        void indexChunks(List<VectorChunk> chunks) {
            rwLock.writeLock().lock();
            try {
                for (VectorChunk chunk : chunks) {
                    indexChunkInternal(chunk);
                }
            } finally {
                rwLock.writeLock().unlock();
            }
        }

        private void indexChunkInternal(VectorChunk chunk) {
            UUID chunkId = chunk.id();
            String docId = chunk.documentId();

            if (chunksById.containsKey(chunkId)) {
                removeChunkInternal(chunkId);
            }

            List<String> tokens = tokenize(chunk.content());
            int docLength = tokens.size();

            Map<String, List<Integer>> termPositions = new HashMap<>();
            for (int pos = 0; pos < tokens.size(); pos++) {
                termPositions.computeIfAbsent(tokens.get(pos), k -> new ArrayList<>()).add(pos);
            }

            for (Map.Entry<String, List<Integer>> entry : termPositions.entrySet()) {
                String term = entry.getKey();
                List<Integer> positions = entry.getValue();
                Posting posting = new Posting(
                        chunkId,
                        docId,
                        chunk.pageNumber(),
                        chunk.headingPath(),
                        chunk.content(),
                        positions.size(),
                        positions
                );
                postingsByTerm.computeIfAbsent(term, k -> new ArrayList<>()).add(posting);
            }

            IndexedChunkMetadata meta = new IndexedChunkMetadata(
                    chunkId,
                    docId,
                    chunk.pageNumber(),
                    chunk.headingPath(),
                    chunk.content(),
                    docLength,
                    termPositions.keySet()
            );

            chunksById.put(chunkId, meta);
            docToChunks.computeIfAbsent(docId, k -> new HashSet<>()).add(chunkId);

            totalChunks++;
            totalTerms += docLength;
            avgdl = (totalChunks > 0) ? (double) totalTerms / totalChunks : 0.0;
        }

        void removeDocument(String documentId) {
            rwLock.writeLock().lock();
            try {
                Set<UUID> chunkIds = docToChunks.remove(documentId);
                if (chunkIds == null || chunkIds.isEmpty()) return;

                for (UUID chunkId : chunkIds) {
                    removeChunkInternal(chunkId);
                }
            } finally {
                rwLock.writeLock().unlock();
            }
        }

        void removeChunk(UUID chunkId) {
            rwLock.writeLock().lock();
            try {
                removeChunkInternal(chunkId);
            } finally {
                rwLock.writeLock().unlock();
            }
        }

        private void removeChunkInternal(UUID chunkId) {
            IndexedChunkMetadata meta = chunksById.remove(chunkId);
            if (meta == null) return;

            for (String term : meta.uniqueTerms()) {
                List<Posting> postings = postingsByTerm.get(term);
                if (postings != null) {
                    postings.removeIf(p -> p.chunkId().equals(chunkId));
                    if (postings.isEmpty()) {
                        postingsByTerm.remove(term);
                    }
                }
            }

            Set<UUID> docChunkSet = docToChunks.get(meta.documentId());
            if (docChunkSet != null) {
                docChunkSet.remove(chunkId);
                if (docChunkSet.isEmpty()) {
                    docToChunks.remove(meta.documentId());
                }
            }

            totalTerms -= meta.docLength();
            totalChunks--;
            avgdl = (totalChunks > 0) ? (double) totalTerms / totalChunks : 0.0;
        }

        List<ScoredPosting> search(String query, int topK) {
            if (query == null || query.isBlank() || topK <= 0) {
                return Collections.emptyList();
            }

            rwLock.readLock().lock();
            try {
                if (totalChunks == 0) {
                    return Collections.emptyList();
                }

                List<String> queryTokens = tokenize(query);
                if (queryTokens.isEmpty()) {
                    return Collections.emptyList();
                }

                Set<String> uniqueTerms = new LinkedHashSet<>(queryTokens);
                Map<UUID, Double> chunkScores = new HashMap<>();
                double currentAvgdl = (avgdl > 0) ? avgdl : 1.0;
                int n = totalChunks;

                for (String term : uniqueTerms) {
                    List<Posting> postings = postingsByTerm.get(term);
                    if (postings == null || postings.isEmpty()) continue;

                    int df = postings.size();
                    // Smoothed Robertson / Lucene IDF: ln(1 + (N - df + 0.5) / (df + 0.5))
                    double idf = Math.log(1.0 + (double) (n - df + 0.5) / (df + 0.5));
                    if (idf <= 0.0) {
                        idf = 0.0001; // Clamp near-zero floor
                    }

                    for (Posting p : postings) {
                        IndexedChunkMetadata meta = chunksById.get(p.chunkId());
                        if (meta == null) continue;

                        int tf = p.termFrequency();
                        int docLength = meta.docLength();

                        double lengthNorm = 1.0 - B + B * ((double) docLength / currentAvgdl);
                        double tfWeight = (tf * (K1 + 1.0)) / (tf + K1 * lengthNorm);
                        double score = idf * tfWeight;

                        chunkScores.merge(p.chunkId(), score, Double::sum);
                    }
                }

                if (chunkScores.isEmpty()) {
                    return Collections.emptyList();
                }

                PriorityQueue<ScoredPosting> minHeap = new PriorityQueue<>(
                        topK, Comparator.comparingDouble(ScoredPosting::bm25Score)
                );

                for (Map.Entry<UUID, Double> entry : chunkScores.entrySet()) {
                    UUID chunkId = entry.getKey();
                    double score = entry.getValue();
                    IndexedChunkMetadata meta = chunksById.get(chunkId);
                    if (meta == null) continue;

                    ScoredPosting sp = new ScoredPosting(
                            chunkId,
                            meta.documentId(),
                            meta.pageNumber(),
                            meta.headingPath(),
                            meta.content(),
                            score
                    );

                    if (minHeap.size() < topK) {
                        minHeap.offer(sp);
                    } else if (score > minHeap.peek().bm25Score()) {
                        minHeap.poll();
                        minHeap.offer(sp);
                    }
                }

                List<ScoredPosting> results = new ArrayList<>(minHeap);
                results.sort(Comparator.comparingDouble(ScoredPosting::bm25Score).reversed());
                return results;
            } finally {
                rwLock.readLock().unlock();
            }
        }

        int size() {
            rwLock.readLock().lock();
            try {
                return totalChunks;
            } finally {
                rwLock.readLock().unlock();
            }
        }

        void clear() {
            rwLock.writeLock().lock();
            try {
                postingsByTerm.clear();
                docToChunks.clear();
                chunksById.clear();
                totalChunks = 0;
                totalTerms = 0;
                avgdl = 0.0;
            } finally {
                rwLock.writeLock().unlock();
            }
        }

        private static List<String> tokenize(String text) {
            if (text == null || text.isBlank()) return Collections.emptyList();
            List<String> list = new ArrayList<>();
            Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
            while (matcher.find()) {
                String token = matcher.group();
                if (token.length() > 1) {
                    list.add(token);
                }
            }
            return list;
        }
    }

    private record IndexedChunkMetadata(
            UUID chunkId,
            String documentId,
            int pageNumber,
            String headingPath,
            String content,
            int docLength,
            Set<String> uniqueTerms
    ) {}
}
