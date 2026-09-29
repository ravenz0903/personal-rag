package com.tracker.rag.repository.vector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class VectorSerializationUtilsTest {

    @Test
    @DisplayName("VectorSerializationUtils: IEEE 754 float array roundtrip preserves exact values")
    void testSerializationRoundtrip() {
        float[] floats = new float[768];
        Random rng = new Random(42);
        for (int i = 0; i < floats.length; i++) {
            floats[i] = rng.nextFloat() * 200.0f - 100.0f;
        }

        byte[] bytes = VectorSerializationUtils.floatsToBytes(floats);
        assertThat(bytes.length).isEqualTo(768 * 4);

        float[] deserialized = VectorSerializationUtils.bytesToFloats(bytes);
        assertThat(deserialized).isEqualTo(floats);
    }

    @Test
    @DisplayName("VectorSerializationUtils: null and empty handling")
    void testNullAndEmptyHandling() {
        assertThat(VectorSerializationUtils.floatsToBytes(null)).isEmpty();
        assertThat(VectorSerializationUtils.bytesToFloats(null)).isEmpty();
        assertThat(VectorSerializationUtils.bytesToFloats(new byte[0])).isEmpty();

        // Length not multiple of 4 throws
        assertThatThrownBy(() -> VectorSerializationUtils.bytesToFloats(new byte[]{1, 2, 3}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("VectorSerializationUtils: Cosine similarity mathematical invariants")
    void testCosineSimilarityInvariants() {
        float[] v1 = new float[]{1.0f, 0.0f, 0.0f};
        float[] v2 = new float[]{1.0f, 0.0f, 0.0f};
        float[] v3 = new float[]{0.0f, 1.0f, 0.0f};
        float[] v4 = new float[]{-1.0f, 0.0f, 0.0f};
        float[] zero = new float[]{0.0f, 0.0f, 0.0f};

        // Identical vectors = 1.0
        assertThat(VectorSerializationUtils.cosineSimilarity(v1, v2)).isCloseTo(1.0, offset(1e-6));

        // Orthogonal vectors = 0.0
        assertThat(VectorSerializationUtils.cosineSimilarity(v1, v3)).isCloseTo(0.0, offset(1e-6));

        // Opposite vectors = -1.0
        assertThat(VectorSerializationUtils.cosineSimilarity(v1, v4)).isCloseTo(-1.0, offset(1e-6));

        // Zero vector or null = 0.0
        assertThat(VectorSerializationUtils.cosineSimilarity(v1, zero)).isCloseTo(0.0, offset(1e-6));
        assertThat(VectorSerializationUtils.cosineSimilarity(null, v1)).isCloseTo(0.0, offset(1e-6));
        assertThat(VectorSerializationUtils.cosineSimilarity(v1, new float[]{1.0f, 2.0f})).isCloseTo(0.0, offset(1e-6));
    }
}
