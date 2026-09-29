package com.tracker.rag.challenger;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.domain.UnscopedQueryException;
import com.tracker.rag.repository.vector.EmbeddedVectorStore;
import com.tracker.rag.repository.vector.ScoredChunk;
import com.tracker.rag.repository.vector.VectorChunk;
import com.tracker.rag.repository.vector.VectorSerializationUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

/**
 * Empirical and adversarial challenge test suite for Milestone 1 Vector Storage & Cosine Similarity.
 * Authored by Challenger 2 (teamwork_preview_challenger).
 */
@DisplayName("Challenger 2: Vector Storage & Cosine Similarity Adversarial Stress Suite")
public class Milestone1VectorStorageChallengerTest {

    private EmbeddedVectorStore store;

    @BeforeEach
    void setUp() {
        store = new EmbeddedVectorStore();
    }

    @Nested
    @DisplayName("Mathematical Invariants & Scaling")
    class CosineMathInvariants {

        @Test
        @DisplayName("Identical vectors evaluate to exactly 1.0 across varying dimensions and directions")
        void testIdenticalVectorsExactMatch() {
            // 2D positive
            float[] v1 = new float[]{3.0f, 4.0f};
            assertThat(VectorSerializationUtils.cosineSimilarity(v1, v1)).isCloseTo(1.0, offset(1e-9));

            // Negative coordinates identical
            float[] vNeg = new float[]{-5.5f, -12.3f, -0.01f};
            assertThat(VectorSerializationUtils.cosineSimilarity(vNeg, vNeg)).isCloseTo(1.0, offset(1e-9));

            // 768-dimensional synthetic embedding
            float[] v768 = new float[768];
            Random rng = new Random(1337);
            for (int i = 0; i < 768; i++) {
                v768[i] = rng.nextFloat() * 10.0f - 5.0f;
            }
            assertThat(VectorSerializationUtils.cosineSimilarity(v768, v768)).isCloseTo(1.0, offset(1e-9));
        }

        @Test
        @DisplayName("Orthogonal vectors evaluate to exactly 0.0")
        void testOrthogonalVectorsExactZero() {
            // Standard Cartesian axes
            assertThat(VectorSerializationUtils.cosineSimilarity(new float[]{1, 0, 0}, new float[]{0, 1, 0})).isEqualTo(0.0);
            assertThat(VectorSerializationUtils.cosineSimilarity(new float[]{0, 0, 1}, new float[]{1, 0, 0})).isEqualTo(0.0);

            // Arbitrary orthogonal pair: dot product = 3*(-4) + 4*(3) = 0
            float[] u = new float[]{3.0f, 4.0f};
            float[] v = new float[]{-4.0f, 3.0f};
            assertThat(VectorSerializationUtils.cosineSimilarity(u, v)).isCloseTo(0.0, offset(1e-9));

            // High dimensional orthogonal vectors
            float[] h1 = new float[768];
            float[] h2 = new float[768];
            for (int i = 0; i < 384; i++) h1[i] = 1.0f;
            for (int i = 384; i < 768; i++) h2[i] = 1.0f;
            assertThat(VectorSerializationUtils.cosineSimilarity(h1, h2)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Opposite vectors evaluate to exactly -1.0")
        void testOppositeVectorsExactNegativeOne() {
            float[] v1 = new float[]{1.0f, 0.0f};
            float[] vOpp = new float[]{-1.0f, 0.0f};
            assertThat(VectorSerializationUtils.cosineSimilarity(v1, vOpp)).isCloseTo(-1.0, offset(1e-9));

            // Opposite 768-dim
            float[] v768 = new float[768];
            float[] v768Opp = new float[768];
            for (int i = 0; i < 768; i++) {
                v768[i] = (i + 1) * 0.5f;
                v768Opp[i] = -(i + 1) * 0.5f;
            }
            assertThat(VectorSerializationUtils.cosineSimilarity(v768, v768Opp)).isCloseTo(-1.0, offset(1e-9));
        }

        @Test
        @DisplayName("Scale invariance: unnormalized vectors with same angle produce identical similarity")
        void testScaleInvarianceNormalizedVsUnnormalized() {
            float[] dir = new float[]{1.0f, 2.0f, 3.0f};
            float[] scaledSmall = new float[]{0.01f, 0.02f, 0.03f};
            float[] scaledLarge = new float[]{100.0f, 200.0f, 300.0f};

            double simSelf = VectorSerializationUtils.cosineSimilarity(dir, dir);
            double simSmall = VectorSerializationUtils.cosineSimilarity(dir, scaledSmall);
            double simLarge = VectorSerializationUtils.cosineSimilarity(dir, scaledLarge);

            assertThat(simSelf).isCloseTo(1.0, offset(1e-7));
            assertThat(simSmall).isCloseTo(1.0, offset(1e-7));
            assertThat(simLarge).isCloseTo(1.0, offset(1e-7));
            assertThat(simSmall).isCloseTo(simLarge, offset(1e-9));
        }
    }

    @Nested
    @DisplayName("Floating Point Edge Cases & Degenerate Inputs")
    class EdgeCasesAndRobustness {

        @Test
        @DisplayName("Zero vectors return 0.0 without division by zero or NaN")
        void testZeroVectorsReturnZero() {
            float[] zero = new float[]{0.0f, 0.0f, 0.0f};
            float[] normal = new float[]{1.0f, 2.0f, 3.0f};

            assertThat(VectorSerializationUtils.cosineSimilarity(zero, zero)).isEqualTo(0.0);
            assertThat(VectorSerializationUtils.cosineSimilarity(zero, normal)).isEqualTo(0.0);
            assertThat(VectorSerializationUtils.cosineSimilarity(normal, zero)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Null and dimension mismatch gracefully return 0.0")
        void testNullAndDimensionMismatch() {
            float[] v1 = new float[]{1.0f, 2.0f};
            float[] v2 = new float[]{1.0f, 2.0f, 3.0f};

            assertThat(VectorSerializationUtils.cosineSimilarity(null, v1)).isEqualTo(0.0);
            assertThat(VectorSerializationUtils.cosineSimilarity(v1, null)).isEqualTo(0.0);
            assertThat(VectorSerializationUtils.cosineSimilarity(null, null)).isEqualTo(0.0);
            assertThat(VectorSerializationUtils.cosineSimilarity(v1, v2)).isEqualTo(0.0);
            assertThat(VectorSerializationUtils.cosineSimilarity(new float[0], new float[0])).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Extreme magnitudes: near float overflow/underflow do not crash")
        void testExtremeMagnitudes() {
            // Near float max (e.g. 1e18)
            float[] huge = new float[]{1e18f, 1e18f};
            double simHuge = VectorSerializationUtils.cosineSimilarity(huge, huge);
            assertThat(simHuge).isCloseTo(1.0, offset(1e-6));

            // Very small non-zero float
            float[] tiny = new float[]{1e-15f, 1e-15f};
            double simTiny = VectorSerializationUtils.cosineSimilarity(tiny, tiny);
            assertThat(simTiny).isCloseTo(1.0, offset(1e-6));
        }

        @Test
        @DisplayName("Float roundtrip preserves bitwise exact float values through serialization")
        void testBitwiseExactSerialization() {
            float[] testFloats = new float[]{
                0.0f, -0.0f, 1.0f, -1.0f, Float.MIN_VALUE, Float.MAX_VALUE,
                (float) Math.PI, (float) Math.E, 12345.678f
            };
            byte[] bytes = VectorSerializationUtils.floatsToBytes(testFloats);
            float[] recovered = VectorSerializationUtils.bytesToFloats(bytes);

            assertThat(recovered.length).isEqualTo(testFloats.length);
            for (int i = 0; i < testFloats.length; i++) {
                assertThat(Float.floatToRawIntBits(recovered[i]))
                    .isEqualTo(Float.floatToRawIntBits(testFloats[i]));
            }
        }
    }

    @Nested
    @DisplayName("Bounded Top-K Min-Heap Priority Queue Selection")
    class TopKPriorityQueueOrdering {

        @Test
        @DisplayName("Top-K returns strictly descending order and exact requested count")
        void testTopKOrderingAndBound() {
            float[] query = new float[]{1.0f, 0.0f, 0.0f};

            // Insert 200 candidates with varied angles
            List<VectorChunk> chunks = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                // Angle ranging from 0 to 180 degrees
                double angleRad = (Math.PI * i) / 199.0;
                float x = (float) Math.cos(angleRad);
                float y = (float) Math.sin(angleRad);
                chunks.add(new VectorChunk(
                    UUID.randomUUID(),
                    "doc-" + i,
                    DomainScope.ACADEMICS,
                    1,
                    null,
                    "Item " + i,
                    new float[]{x, y, 0.0f}
                ));
            }
            store.saveAll(chunks);

            int topK = 15;
            List<ScoredChunk> results = store.similaritySearch(query, DomainScope.ACADEMICS, topK);

            // Assert bounded count
            assertThat(results).hasSize(topK);

            // Assert strictly non-ascending (descending) similarity scores
            for (int i = 0; i < results.size() - 1; i++) {
                assertThat(results.get(i).score())
                    .as("Score at index " + i + " must be >= score at index " + (i + 1))
                    .isGreaterThanOrEqualTo(results.get(i + 1).score());
            }

            // Highest score should be closest to identical (Item 0 with angle 0)
            assertThat(results.get(0).content()).isEqualTo("Item 0");
            assertThat(results.get(0).score()).isCloseTo(1.0, offset(1e-5));
        }

        @Test
        @DisplayName("Top-K handles negative scores correctly in descending order")
        void testNegativeScoresRanking() {
            float[] query = new float[]{1.0f, 0.0f};

            // All candidates in negative cosine territory (x < 0)
            VectorChunk c1 = new VectorChunk(UUID.randomUUID(), "d1", DomainScope.ACADEMICS, 1, null, "Worst (-1.0)", new float[]{-1.0f, 0.0f});
            VectorChunk c2 = new VectorChunk(UUID.randomUUID(), "d2", DomainScope.ACADEMICS, 1, null, "MidNeg (-0.707)", new float[]{-1.0f, 1.0f});
            VectorChunk c3 = new VectorChunk(UUID.randomUUID(), "d3", DomainScope.ACADEMICS, 1, null, "MildNeg (-0.1)", new float[]{-0.1f, 1.0f});

            store.saveAll(List.of(c1, c2, c3));

            List<ScoredChunk> results = store.similaritySearch(query, DomainScope.ACADEMICS, 2);
            assertThat(results).hasSize(2);

            // MildNeg should be highest score among the three
            assertThat(results.get(0).content()).isEqualTo("MildNeg (-0.1)");
            assertThat(results.get(1).content()).isEqualTo("MidNeg (-0.707)");
            assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
        }

        @Test
        @DisplayName("Top-K edge values: topK <= 0, topK > totalCandidates")
        void testTopKEdgeValues() {
            float[] query = new float[]{1.0f, 0.0f};
            VectorChunk c1 = new VectorChunk(UUID.randomUUID(), "d1", DomainScope.CAREER, 1, null, "C1", query);
            store.save(c1);

            // topK = 0 returns empty
            assertThat(store.similaritySearch(query, DomainScope.CAREER, 0)).isEmpty();

            // topK = -1 returns empty
            assertThat(store.similaritySearch(query, DomainScope.CAREER, -1)).isEmpty();

            // topK = 100 with 1 candidate returns exactly 1 candidate
            List<ScoredChunk> results = store.similaritySearch(query, DomainScope.CAREER, 100);
            assertThat(results).hasSize(1);
            assertThat(results.get(0).content()).isEqualTo("C1");
        }

        @Test
        @DisplayName("Identical score ties are handled smoothly without exception")
        void testIdenticalScoreTies() {
            float[] query = new float[]{1.0f, 0.0f};
            List<VectorChunk> chunks = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                chunks.add(new VectorChunk(UUID.randomUUID(), "doc-" + i, DomainScope.ACADEMICS, 1, null, "Tie " + i, query));
            }
            store.saveAll(chunks);

            List<ScoredChunk> results = store.similaritySearch(query, DomainScope.ACADEMICS, 5);
            assertThat(results).hasSize(5);
            for (ScoredChunk sc : results) {
                assertThat(sc.score()).isCloseTo(1.0, offset(1e-6));
            }
        }
    }
}
