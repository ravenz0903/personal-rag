package com.tracker.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/**
 * Service for local Ollama interactions (chat & nomic-embed-text embeddings).
 * Enforces explicit num_ctx: 8192 per hardware specification.
 * Includes graceful offline fallback for resilient local operation.
 */
@Service
public class OllamaClientService {

    private static final Logger log = LoggerFactory.getLogger(OllamaClientService.class);
    private static final int EMBEDDING_DIM = 768;

    @Value("${ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${ollama.chat-model:llama3.1:8b}")
    private String chatModel;

    @Value("${ollama.embed-model:nomic-embed-text}")
    private String embedModel;

    @Value("${ollama.num-ctx:8192}")
    private int numCtx;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OllamaClientService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Generate 768-dimensional float embedding using Ollama nomic-embed-text.
     * Falls back to deterministic normalized hashing if Ollama is unreachable.
     */
    public float[] generateEmbedding(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new float[EMBEDDING_DIM];
        }

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", embedModel,
                    "prompt", text
            );
            String jsonPayload = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/embeddings"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode embeddingNode = root.get("embedding");
                if (embeddingNode != null && embeddingNode.isArray()) {
                    float[] result = new float[embeddingNode.size()];
                    for (int i = 0; i < embeddingNode.size(); i++) {
                        result[i] = (float) embeddingNode.get(i).asDouble();
                    }
                    return result;
                }
            }
        } catch (Exception e) {
            log.warn("[OllamaClient] Ollama embed call failed ({}), using deterministic fallback", e.getMessage());
        }

        return generateDeterministicFallbackEmbedding(text);
    }

    /**
     * Synchronous chat completion with num_ctx: 8192.
     */
    public String generateChat(String systemPrompt, String userMessage) {
        try {
            Map<String, Object> options = Map.of("num_ctx", numCtx);
            List<Map<String, String>> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(Map.of("role", "system", "content", systemPrompt));
            }
            messages.add(Map.of("role", "user", "content", userMessage));

            Map<String, Object> requestBody = Map.of(
                    "model", chatModel,
                    "messages", messages,
                    "stream", false,
                    "options", options
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode messageNode = root.path("message").path("content");
                if (!messageNode.isMissingNode()) {
                    return messageNode.asText();
                }
            }
        } catch (Exception e) {
            log.warn("[OllamaClient] Chat generation failed: {}", e.getMessage());
        }

        return "Local Ollama service at " + baseUrl + " is currently offline or starting up. " +
                "Model: " + chatModel + ". Please ensure Ollama is running.";
    }

    /**
     * Stream response chunks via callback for SSE.
     */
    public void streamChat(String systemPrompt, String userMessage, Consumer<String> chunkConsumer, Runnable onComplete) {
        try {
            Map<String, Object> options = Map.of("num_ctx", numCtx);
            List<Map<String, String>> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(Map.of("role", "system", "content", systemPrompt));
            }
            messages.add(Map.of("role", "user", "content", userMessage));

            Map<String, Object> requestBody = Map.of(
                    "model", chatModel,
                    "messages", messages,
                    "stream", true,
                    "options", options
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    JsonNode node = objectMapper.readTree(line);
                    String chunk = node.path("message").path("content").asText("");
                    if (!chunk.isEmpty()) {
                        chunkConsumer.accept(chunk);
                    }
                    if (node.path("done").asBoolean(false)) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[OllamaClient] Streaming chat failed: {}", e.getMessage());
            chunkConsumer.accept("\n[Offline Mode] Unable to connect to local Ollama at " + baseUrl + ".\n");
        } finally {
            if (onComplete != null) {
                onComplete.run();
            }
        }
    }

    /**
     * Generates normalized 768-dimensional embedding from SHA-256 hash when offline.
     */
    public static float[] generateDeterministicFallbackEmbedding(String text) {
        float[] vector = new float[EMBEDDING_DIM];
        if (text == null || text.trim().isEmpty()) {
            return vector;
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            Random rng = new Random(java.nio.ByteBuffer.wrap(hash, 0, 8).getLong());

            double normSq = 0.0;
            for (int i = 0; i < EMBEDDING_DIM; i++) {
                vector[i] = (float) (rng.nextGaussian());
                normSq += vector[i] * vector[i];
            }

            float norm = (float) Math.sqrt(normSq);
            if (norm > 0) {
                for (int i = 0; i < EMBEDDING_DIM; i++) {
                    vector[i] /= norm;
                }
            }
        } catch (Exception e) {
            Arrays.fill(vector, 0.0f);
        }
        return vector;
    }
}
