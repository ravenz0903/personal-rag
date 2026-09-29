package com.tracker.rag.e2e.stub;

import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

/**
 * Deterministic inference client stub with loopback-only network guard
 * enforcing Zero Cloud Leakage per survey_report.md § 2.1.3 and PROJECT.md F28 & F29.
 */
public class LoopbackEnforcingClientStub {

    public static final int EMBEDDING_DIMENSION = 768;

    /**
     * Verifies that the given endpoint is strictly bound to a loopback address.
     * Throws SecurityException if any outbound connection to a non-loopback address is attempted.
     */
    public static void verifyLoopbackOnly(String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            throw new IllegalArgumentException("Endpoint URL cannot be empty");
        }
        try {
            URI uri = URI.create(endpointUrl);
            String host = uri.getHost();
            if (host == null) {
                throw new SecurityException("Zero Cloud Leakage Violation: Malformed host in URL: " + endpointUrl);
            }
            if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host)) {
                return; // Valid loopback
            }
            InetAddress addr = InetAddress.getByName(host);
            if (!addr.isLoopbackAddress()) {
                throw new SecurityException("Zero Cloud Leakage Violation: Attempted outbound network connection to non-loopback host: " + endpointUrl);
            }
        } catch (Exception e) {
            throw new SecurityException("Zero Cloud Leakage Violation: Host verification failed: " + e.getMessage(), e);
        }
    }

    /**
     * Generates a deterministic, L2-normalized 768-dimensional float vector seeded by SHA-256 of text.
     */
    public static float[] generateDeterministicEmbedding(String text) {
        if (text == null) text = "";
        String[] words = text.toLowerCase().split("\\W+");
        float[] vector = new float[EMBEDDING_DIMENSION];
        boolean hasWords = false;

        for (String word : words) {
            if (word.isEmpty()) continue;
            hasWords = true;
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(word.getBytes(StandardCharsets.UTF_8));
                long seed = ByteBuffer.wrap(hash).getLong();
                Random rng = new Random(seed);
                for (int i = 0; i < EMBEDDING_DIMENSION; i++) {
                    vector[i] += rng.nextFloat() - 0.5f;
                }
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("SHA-256 not available", e);
            }
        }

        if (!hasWords) {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
                long seed = ByteBuffer.wrap(hash).getLong();
                Random rng = new Random(seed);
                for (int i = 0; i < EMBEDDING_DIMENSION; i++) {
                    vector[i] = rng.nextFloat() - 0.5f;
                }
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("SHA-256 not available", e);
            }
        }

        float sumSquares = 0.0f;
        for (int i = 0; i < EMBEDDING_DIMENSION; i++) {
            sumSquares += vector[i] * vector[i];
        }
        float norm = (float) Math.sqrt(sumSquares);
        if (norm > 0) {
            for (int i = 0; i < EMBEDDING_DIMENSION; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    /**
     * Encloses document context in passive XML tags for prompt injection defense.
     */
    public static String wrapUntrustedContext(String rawDocumentText) {
        return "<untrusted_document_context>\n" +
                (rawDocumentText != null ? rawDocumentText.replace("\0", "") : "") +
                "\n</untrusted_document_context>";
    }
}
