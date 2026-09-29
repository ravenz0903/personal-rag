package com.tracker.rag.repository.vector;

import com.tracker.rag.domain.DomainScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.PriorityQueue;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoredChunkTest {

    @Test
    @DisplayName("ScoredChunk: compareTo orders ascending by score for min-heap")
    void testCompareToAscending() {
        VectorChunk c1 = new VectorChunk(UUID.randomUUID(), "doc-1", DomainScope.ACADEMICS, 1, null, "txt1", new float[]{1.0f});
        VectorChunk c2 = new VectorChunk(UUID.randomUUID(), "doc-2", DomainScope.ACADEMICS, 1, null, "txt2", new float[]{1.0f});

        ScoredChunk scLow = new ScoredChunk(c1, 0.45);
        ScoredChunk scHigh = new ScoredChunk(c2, 0.95);

        assertThat(scLow.compareTo(scHigh)).isLessThan(0);
        assertThat(scHigh.compareTo(scLow)).isGreaterThan(0);
        assertThat(scLow.compareTo(new ScoredChunk(c1, 0.45))).isEqualTo(0);

        // Min-heap behavior: head is lowest score
        PriorityQueue<ScoredChunk> pq = new PriorityQueue<>();
        pq.offer(scHigh);
        pq.offer(scLow);
        assertThat(pq.peek()).isEqualTo(scLow);
    }

    @Test
    @DisplayName("ScoredChunk: Delegation getters return underlying chunk attributes")
    void testDelegationGetters() {
        UUID id = UUID.randomUUID();
        VectorChunk chunk = new VectorChunk(id, "doc-10", DomainScope.CAREER, 2, "H2 > Skills", "Java 21", new float[]{0.1f, 0.2f});
        ScoredChunk scored = new ScoredChunk(chunk, 0.88);

        assertThat(scored.id()).isEqualTo(id);
        assertThat(scored.documentId()).isEqualTo("doc-10");
        assertThat(scored.domain()).isEqualTo(DomainScope.CAREER);
        assertThat(scored.pageNumber()).isEqualTo(2);
        assertThat(scored.headingPath()).isEqualTo("H2 > Skills");
        assertThat(scored.content()).isEqualTo("Java 21");
        assertThat(scored.embedding()).isEqualTo(new float[]{0.1f, 0.2f});
    }

    @Test
    @DisplayName("ScoredChunk: Null chunk throws NullPointerException")
    void testNullChunkThrows() {
        assertThatThrownBy(() -> new ScoredChunk(null, 0.5))
                .isInstanceOf(NullPointerException.class);
    }
}
