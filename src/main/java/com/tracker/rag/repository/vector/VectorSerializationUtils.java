package com.tracker.rag.repository.vector;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * High-performance binary serialization utilities for IEEE 754 32-bit float vector arrays.
 */
public final class VectorSerializationUtils {

    private VectorSerializationUtils() {}

    /**
     * Serializes a float array into an IEEE 754 big-endian byte array.
     * Size in bytes = floats.length * 4.
     */
    public static byte[] floatsToBytes(float[] floats) {
        if (floats == null) return new byte[0];
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * Float.BYTES)
                                      .order(ByteOrder.BIG_ENDIAN);
        buffer.asFloatBuffer().put(floats);
        return buffer.array();
    }

    /**
     * Deserializes an IEEE 754 big-endian byte array back into a float array.
     */
    public static float[] bytesToFloats(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return new float[0];
        if (bytes.length % Float.BYTES != 0) {
            throw new IllegalArgumentException("Byte array length " + bytes.length + " is not a multiple of " + Float.BYTES);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        float[] floats = new float[bytes.length / Float.BYTES];
        buffer.asFloatBuffer().get(floats);
        return floats;
    }

    /**
     * Computes the exact mathematical cosine similarity between two float vectors.
     *
     * Similarity = (A . B) / (||A|| * ||B||)
     *
     * @return similarity score in range [-1.0, 1.0], or 0.0 if either vector has norm 0.
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0.0;
        }
        double dotProduct = 0.0;
        double normASquared = 0.0;
        double normBSquared = 0.0;

        for (int i = 0; i < a.length; i++) {
            double valA = a[i];
            double valB = b[i];
            dotProduct += valA * valB;
            normASquared += valA * valA;
            normBSquared += valB * valB;
        }

        if (normASquared <= 0.0 || normBSquared <= 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normASquared) * Math.sqrt(normBSquared));
    }
}
