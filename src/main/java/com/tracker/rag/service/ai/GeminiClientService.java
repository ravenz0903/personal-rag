package com.tracker.rag.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.service.cache.EmbeddingCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/** Hosted inference only. Errors never become fabricated vectors or successful answers. */
@Service
@ConditionalOnProperty(name = "ai.provider", havingValue = "gemini", matchIfMissing = true)
public class GeminiClientService implements AiClient {
    @Override public String embeddingSpace() { return embeddingModel + ":768:retrieval-v1"; }
    private final ObjectMapper mapper;
    private final EmbeddingCache cache;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String apiKey, baseUrl, chatModel, embeddingModel;
    private final boolean personalEnabled;
    private final int dailyLimit, minuteLimit;
    private final Semaphore concurrency = new Semaphore(2);
    private LocalDate day = LocalDate.now(ZoneOffset.UTC);
    private long minute = System.currentTimeMillis() / 60_000;
    private int dailyCalls, minuteCalls;

    public GeminiClientService(ObjectMapper mapper, EmbeddingCache cache,
            @Value("${gemini.api-key:}") String apiKey,
            @Value("${gemini.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl,
            @Value("${gemini.chat-model:gemini-2.5-flash}") String chatModel,
            @Value("${gemini.embedding-model:gemini-embedding-2}") String embeddingModel,
            @Value("${ai.personal-enabled:false}") boolean personalEnabled,
            @Value("${ai.max-daily-requests:100}") int dailyLimit,
            @Value("${ai.max-minute-requests:20}") int minuteLimit) {
        this.mapper = mapper; this.cache = cache; this.apiKey = apiKey; this.baseUrl = baseUrl;
        this.chatModel = chatModel; this.embeddingModel = embeddingModel;
        this.personalEnabled = personalEnabled; this.dailyLimit = dailyLimit; this.minuteLimit = minuteLimit;
        if (!chatModel.matches("[a-zA-Z0-9.-]+") || !embeddingModel.matches("[a-zA-Z0-9.-]+"))
            throw new IllegalArgumentException("Invalid model identifier");
        URI endpoint = URI.create(baseUrl);
        if (!"https".equals(endpoint.getScheme()) && !Set.of("localhost", "127.0.0.1").contains(endpoint.getHost()))
            throw new IllegalArgumentException("AI endpoint must use HTTPS");
    }

    @Override public float[] generateEmbedding(String text, DomainScope domain) { return embed(text, domain, false); }
    @Override public float[] generateQueryEmbedding(String text, DomainScope domain) { return embed(text, domain, true); }

    private float[] embed(String text, DomainScope domain, boolean query) {
        DomainScope.validateScope(domain);
        if (domain == DomainScope.PERSONAL && !personalEnabled)
            throw new AiServiceException(403, "Cloud AI is disabled for Personal documents. Your records remain available.");
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Embedding text cannot be empty");
        if (text.length() > 24_000) throw new IllegalArgumentException("Document section is too large. Split it into smaller sections.");
        String key = embeddingModel + ":" + (query ? "query:" : "document:") + text;
        Optional<float[]> cached = cache.get(domain, key);
        if (cached.isPresent()) return cached.get();
        String prepared = query ? "task: search result | query: " + text : "title: none | text: " + text;
        JsonNode root = post(embeddingModel + ":embedContent", Map.of(
                "content", Map.of("parts", List.of(Map.of("text", prepared))), "outputDimensionality", 768));
        JsonNode values = root.path("embedding").path("values");
        if (!values.isArray() || values.size() != 768) throw new AiServiceException(502, "AI returned an invalid embedding. Nothing was saved.");
        float[] vector = new float[768]; double norm = 0;
        for (int i = 0; i < 768; i++) {
            vector[i] = (float) values.get(i).asDouble();
            if (!Float.isFinite(vector[i])) throw new AiServiceException(502, "AI returned an invalid embedding.");
            norm += vector[i] * vector[i];
        }
        if (norm == 0) throw new AiServiceException(502, "AI returned an empty embedding.");
        float length = (float) Math.sqrt(norm);
        for (int i = 0; i < 768; i++) vector[i] /= length;
        cache.put(domain, key, vector);
        return vector;
    }

    @Override public String generateChat(String system, String input) { return complete(system, input, false); }
    @Override public String generateJson(String system, String input) { return complete(system, input, true); }
    private String complete(String system, String input, boolean json) {
        JsonNode root = post(chatModel + ":generateContent", body(system, input, json));
        String answer = text(root);
        if (answer.isBlank()) throw new AiServiceException(502, "AI could not produce an answer. Try rephrasing your question.");
        return answer;
    }
    private Map<String, Object> body(String system, String input, boolean json) {
        if (input == null || input.isBlank() || input.length() > 100_000) throw new IllegalArgumentException("Question or context is empty or too large");
        Map<String, Object> options = new HashMap<>();
        options.put("temperature", 0.1); options.put("maxOutputTokens", 2048);
        if (json) options.put("responseMimeType", "application/json");
        return Map.of("systemInstruction", Map.of("parts", List.of(Map.of("text", system == null ? "" : system))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", input)))), "generationConfig", options);
    }

    @Override public void streamChat(String system, String input, Consumer<String> onChunk, Runnable onComplete) {
        HttpRequest request = request(chatModel + ":streamGenerateContent?alt=sse", body(system, input, false));
        acquire();
        try {
            HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body(); var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                checkStatus(response.statusCode());
                String line; boolean hasText = false;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isBlank() || data.equals("[DONE]")) continue;
                    String chunk = text(mapper.readTree(data));
                    if (!chunk.isEmpty()) { onChunk.accept(chunk); hasText = true; }
                }
                if (!hasText) throw new AiServiceException(502, "AI could not produce an answer.");
            }
            onComplete.run();
        } catch (AiServiceException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AiServiceException(503, "AI request interrupted. Try again."); }
        catch (Exception e) { throw new AiServiceException(503, "AI service is temporarily unavailable. Try again later."); }
        finally { concurrency.release(); }
    }

    private JsonNode post(String operation, Map<String, Object> body) {
        HttpRequest request = request(operation, body);
        acquire();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            checkStatus(response.statusCode());
            return mapper.readTree(response.body());
        } catch (AiServiceException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AiServiceException(503, "AI request interrupted. Try again."); }
        catch (Exception e) { throw new AiServiceException(503, "AI service is temporarily unavailable. Try again later."); }
        finally { concurrency.release(); }
    }
    private HttpRequest request(String operation, Map<String, Object> body) {
        if (apiKey == null || apiKey.isBlank()) throw new AiServiceException(503, "AI is not connected yet. Add the Gemini free-tier key to the backend settings.");
        try {
            return HttpRequest.newBuilder(URI.create(baseUrl + "/models/" + operation))
                    .timeout(Duration.ofSeconds(90)).header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        } catch (Exception e) { throw new AiServiceException(503, "AI configuration is invalid."); }
    }
    private void acquire() {
        try {
            if (!concurrency.tryAcquire(30, java.util.concurrent.TimeUnit.SECONDS))
                throw new AiServiceException(429, "AI requests are busy. Please wait and try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new AiServiceException(503, "AI request interrupted.");
        }
        try { reserveRequest(); } catch (RuntimeException e) { concurrency.release(); throw e; }
    }
    private synchronized void reserveRequest() {
        LocalDate now = LocalDate.now(ZoneOffset.UTC);
        long currentMinute = System.currentTimeMillis() / 60_000;
        if (!day.equals(now)) { day = now; dailyCalls = 0; }
        if (minute != currentMinute) { minute = currentMinute; minuteCalls = 0; }
        if (dailyCalls >= dailyLimit || minuteCalls >= minuteLimit)
            throw new AiServiceException(429, "The app's free AI request limit has been reached. Try again after the limit resets.");
        dailyCalls++; minuteCalls++;
    }
    private void checkStatus(int status) {
        if (status == 429) throw new AiServiceException(429, "Gemini's free quota is currently exhausted. Try again later; no paid fallback is used.");
        if (status == 401 || status == 403) throw new AiServiceException(503, "Gemini access was denied. Check the backend API key and free-tier eligibility.");
        if (status < 200 || status >= 300) throw new AiServiceException(502, "Gemini could not complete the request (HTTP " + status + ").");
    }
    private String text(JsonNode root) {
        StringBuilder result = new StringBuilder();
        JsonNode candidate = root.path("candidates").path(0);
        for (JsonNode part : candidate.path("content").path("parts"))
            if (!part.path("thought").asBoolean(false)) result.append(part.path("text").asText(""));
        return result.toString();
    }
}
