package com.tracker.rag.repository.vector;

import com.tracker.rag.domain.DomainScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VectorChunkTest {

    @Test
    @DisplayName("VectorChunk: Valid creation and getters")
    void testValidChunkCreation() {
        UUID id = UUID.randomUUID();
        float[] embedding = new float[]{0.1f, 0.2f, 0.3f};
        VectorChunk chunk = new VectorChunk(id, "doc-1", DomainScope.ACADEMICS, 1, "H1 > Intro", "Content text", embedding);

        assertThat(chunk.id()).isEqualTo(id);
        assertThat(chunk.documentId()).isEqualTo("doc-1");
        assertThat(chunk.domain()).isEqualTo(DomainScope.ACADEMICS);
        assertThat(chunk.pageNumber()).isEqualTo(1);
        assertThat(chunk.headingPath()).isEqualTo("H1 > Intro");
        assertThat(chunk.content()).isEqualTo("Content text");
        assertThat(chunk.embedding()).isEqualTo(embedding);
    }

    @Test
    @DisplayName("VectorChunk: Defensive copying of embedding array")
    void testDefensiveCopying() {
        float[] original = new float[]{1.0f, 2.0f, 3.0f};
        VectorChunk chunk = new VectorChunk(UUID.randomUUID(), "doc-1", DomainScope.PERSONAL, 0, null, "Text", original);

        // Modifying original array should not affect chunk
        original[0] = 99.0f;
        assertThat(chunk.embedding()[0]).isEqualTo(1.0f);

        // Modifying array returned by getter should not affect chunk
        float[] getterArray = chunk.embedding();
        getterArray[1] = 88.0f;
        assertThat(chunk.embedding()[1]).isEqualTo(2.0f);
    }

    @Test
    @DisplayName("VectorChunk: Null validation in compact constructor")
    void testNullValidations() {
        UUID id = UUID.randomUUID();
        float[] emb = new float[]{1.0f};

        assertThatThrownBy(() -> new VectorChunk(null, "doc-1", DomainScope.CAREER, 1, null, "txt", emb))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new VectorChunk(id, null, DomainScope.CAREER, 1, null, "txt", emb))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new VectorChunk(id, "doc-1", null, 1, null, "txt", emb))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new VectorChunk(id, "doc-1", DomainScope.CAREER, 1, null, null, emb))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new VectorChunk(id, "doc-1", DomainScope.CAREER, 1, null, "txt", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("VectorChunk: Negative page number or empty embedding throws IllegalArgumentException")
    void testInvalidPageOrEmptyEmbedding() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new VectorChunk(id, "doc-1", DomainScope.ACADEMICS, -1, null, "txt", new float[]{1.0f}))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new VectorChunk(id, "doc-1", DomainScope.ACADEMICS, 1, null, "txt", new float[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("VectorChunk: equals and hashCode verify structural equality including embedding array")
    void testEqualsAndHashCode() {
        UUID id = UUID.randomUUID();
        VectorChunk c1 = new VectorChunk(id, "doc-1", DomainScope.ACADEMICS, 1, "H1", "Content", new float[]{0.5f, 0.5f});
        VectorChunk c2 = new VectorChunk(id, "doc-1", DomainScope.ACADEMICS, 1, "H1", "Content", new float[]{0.5f, 0.5f});
        VectorChunk c3 = new VectorChunk(id, "doc-1", DomainScope.ACADEMICS, 1, "H1", "Content", new float[]{0.5f, 0.9f});

        assertThat(c1).isEqualTo(c2);
        assertThat(c1.hashCode()).isEqualTo(c2.hashCode());
        assertThat(c1).isNotEqualTo(c3);
    }
}
