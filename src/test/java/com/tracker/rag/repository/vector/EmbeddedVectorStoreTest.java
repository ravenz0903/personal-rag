package com.tracker.rag.repository.vector;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.domain.UnscopedQueryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddedVectorStoreTest {

    private EmbeddedVectorStore store;

    @BeforeEach
    void setUp() {
        store = new EmbeddedVectorStore();
    }

    @Test
    @DisplayName("EmbeddedVectorStore: save, saveAll, count, deleteByDocumentId, deleteById")
    void testBasicCrudOperations() {
        VectorChunk chunk1 = new VectorChunk(UUID.randomUUID(), "doc-1", DomainScope.ACADEMICS, 1, "H1", "Content 1", new float[]{1.0f, 0.0f});
        VectorChunk chunk2 = new VectorChunk(UUID.randomUUID(), "doc-1", DomainScope.ACADEMICS, 2, "H2", "Content 2", new float[]{0.0f, 1.0f});
        VectorChunk chunk3 = new VectorChunk(UUID.randomUUID(), "doc-2", DomainScope.PERSONAL, 1, "H1", "Content 3", new float[]{0.5f, 0.5f});

        store.save(chunk1);
        assertThat(store.count()).isEqualTo(1);
        assertThat(store.countByDomain(DomainScope.ACADEMICS)).isEqualTo(1);
        assertThat(store.countByDomain(DomainScope.PERSONAL)).isEqualTo(0);

        store.saveAll(List.of(chunk2, chunk3));
        assertThat(store.count()).isEqualTo(3);
        assertThat(store.countByDomain(DomainScope.ACADEMICS)).isEqualTo(2);
        assertThat(store.countByDomain(DomainScope.PERSONAL)).isEqualTo(1);

        // Delete by document ID
        store.deleteByDocumentId("doc-1");
        assertThat(store.count()).isEqualTo(1);
        assertThat(store.countByDomain(DomainScope.ACADEMICS)).isEqualTo(0);

        // Delete by chunk ID
        store.deleteById(chunk3.id());
        assertThat(store.count()).isEqualTo(0);
    }

    @Test
    @DisplayName("EmbeddedVectorStore: Similarity search with bounded min-heap top-K selection")
    void testSimilaritySearchAndRanking() {
        float[] query = new float[]{1.0f, 0.0f, 0.0f};

        // Identical direction (score = 1.0)
        VectorChunk c1 = new VectorChunk(UUID.randomUUID(), "doc-1", DomainScope.ACADEMICS, 1, null, "Identical", new float[]{2.0f, 0.0f, 0.0f});
        // 45 degrees (score approx 0.707)
        VectorChunk c2 = new VectorChunk(UUID.randomUUID(), "doc-2", DomainScope.ACADEMICS, 1, null, "45-deg", new float[]{1.0f, 1.0f, 0.0f});
        // 90 degrees / orthogonal (score = 0.0)
        VectorChunk c3 = new VectorChunk(UUID.randomUUID(), "doc-3", DomainScope.ACADEMICS, 1, null, "Orthogonal", new float[]{0.0f, 1.0f, 0.0f});
        // Opposite (score = -1.0)
        VectorChunk c4 = new VectorChunk(UUID.randomUUID(), "doc-4", DomainScope.ACADEMICS, 1, null, "Opposite", new float[]{-1.0f, 0.0f, 0.0f});

        store.saveAll(List.of(c1, c2, c3, c4));

        // Search top 2
        List<ScoredChunk> results = store.similaritySearch(query, DomainScope.ACADEMICS, 2);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).content()).isEqualTo("Identical");
        assertThat(results.get(0).score()).isGreaterThan(0.99);
        assertThat(results.get(1).content()).isEqualTo("45-deg");
        assertThat(results.get(1).score()).isGreaterThan(0.70);
    }

    @Test
    @DisplayName("EmbeddedVectorStore: Strict domain isolation for Personal domain")
    void testPersonalDomainIsolation() {
        float[] vec = new float[]{1.0f, 0.0f};

        VectorChunk academic = new VectorChunk(UUID.randomUUID(), "doc-acad", DomainScope.ACADEMICS, 1, null, "Academic notes", vec);
        VectorChunk personal = new VectorChunk(UUID.randomUUID(), "doc-pers", DomainScope.PERSONAL, 1, null, "Personal diary", vec);
        VectorChunk career = new VectorChunk(UUID.randomUUID(), "doc-car", DomainScope.CAREER, 1, null, "Resume info", vec);

        store.saveAll(List.of(academic, personal, career));

        // Scoped to ACADEMICS: returns 0 personal chunks
        List<ScoredChunk> acadResults = store.similaritySearch(vec, DomainScope.ACADEMICS, 10);
        assertThat(acadResults).hasSize(1);
        assertThat(acadResults.get(0).content()).isEqualTo("Academic notes");

        // Scoped to PUBLIC_DEFAULT [ACADEMICS, CAREER]: returns 0 personal chunks
        List<ScoredChunk> generalResults = store.similaritySearch(vec, DomainScope.PUBLIC_DEFAULT, 10);
        assertThat(generalResults).hasSize(2);
        assertThat(generalResults).noneMatch(sc -> sc.domain() == DomainScope.PERSONAL);

        // Scoped to PERSONAL: returns personal chunk
        List<ScoredChunk> persResults = store.similaritySearch(vec, DomainScope.PERSONAL, 10);
        assertThat(persResults).hasSize(1);
        assertThat(persResults.get(0).content()).isEqualTo("Personal diary");
    }

    @Test
    @DisplayName("EmbeddedVectorStore: Fail-closed validation on null or empty scopes")
    void testFailClosedScopeValidation() {
        float[] vec = new float[]{1.0f, 0.0f};

        assertThatThrownBy(() -> store.similaritySearch(vec, (DomainScope) null, 5))
                .isInstanceOf(UnscopedQueryException.class)
                .hasMessageContaining("fail-closed");

        assertThatThrownBy(() -> store.similaritySearch(vec, Collections.emptySet(), 5))
                .isInstanceOf(UnscopedQueryException.class)
                .hasMessageContaining("fail-closed");

        assertThatThrownBy(() -> store.countByDomain(null))
                .isInstanceOf(UnscopedQueryException.class);
    }
}
