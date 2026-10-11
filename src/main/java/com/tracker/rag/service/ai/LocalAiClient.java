package com.tracker.rag.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.service.cache.EmbeddingCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** Backend-only client of Folio's authenticated main-process inference bridge. */
@Service @Primary @Profile("desktop")
@ConditionalOnProperty(name = "ai.provider", havingValue = "local")
public class LocalAiClient implements AiClient {
    private final ObjectMapper mapper;
    private final EmbeddingCache cache;
    private final URI origin;
    private final String token, space;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public LocalAiClient(ObjectMapper mapper, EmbeddingCache cache,
            @Value("${folio.local-ai.origin:}") String origin,
            @Value("${folio.local-ai.token:}") String token,
            @Value("${folio.local-ai.embedding-space:}") String space) {
        this.mapper = mapper; this.cache = cache;
        this.origin = URI.create(origin); this.token = token; this.space = space;
        if (!"http".equals(this.origin.getScheme()) || !"127.0.0.1".equals(this.origin.getHost()) ||
                this.origin.getPort() < 1 || this.origin.getRawUserInfo() != null || this.origin.getRawQuery() != null ||
                this.origin.getRawFragment() != null || !Set.of("", "/").contains(this.origin.getPath()) ||
                !token.matches("[a-f0-9]{64}") || !space.matches("[a-zA-Z0-9:._-]{10,200}"))
            throw new IllegalArgumentException("Local AI requires a private launcher connection and pinned embedding identity");
    }
    @Override public String embeddingSpace() { return space; }
    public Map<String, Object> status() {
        try {
            JsonNode data = exchange("/status", null, Duration.ofSeconds(2));
            String state = data.path("state").asText("unavailable");
            if (!Set.of("idle", "loading", "working", "ready", "unavailable", "stopped").contains(state)) state = "unavailable";
            String backend = data.path("backend").asText("");
            return Map.of("available", data.path("available").asBoolean(false), "state", state,
                    "backend", Set.of("gpu", "cpu").contains(backend) ? backend : "");
        } catch (AiServiceException error) { return Map.of("available", false, "state", "unavailable", "backend", ""); }
    }
    @Override public float[] generateEmbedding(String text, DomainScope scope) { return embed(text, scope, false); }
    @Override public float[] generateQueryEmbedding(String text, DomainScope scope) { return embed(text, scope, true); }
    private float[] embed(String text, DomainScope scope, boolean query) {
        DomainScope.validateScope(scope);
        if (text == null || text.isBlank() || text.length() > 11950) throw new IllegalArgumentException("Document section is empty or too large");
        String prepared = (query ? "search_query: " : "search_document: ") + text;
        String key = space + ":" + prepared;
        var existing = cache.get(scope, key);
        if (existing.isPresent()) return existing.get();
        JsonNode values = exchange("/embedding", Map.of("input", prepared), Duration.ofSeconds(190))
                .path("data").path(0).path("embedding");
        if (!values.isArray() || values.size() != 768) throw invalidEmbedding();
        float[] vector = new float[768]; double norm = 0;
        for (int i = 0; i < vector.length; i++) {
            if (!values.get(i).isNumber()) throw invalidEmbedding();
            vector[i] = (float) values.get(i).asDouble();
            if (!Float.isFinite(vector[i])) throw invalidEmbedding();
            norm += (double) vector[i] * vector[i];
        }
        if (norm <= 0 || !Double.isFinite(norm)) throw invalidEmbedding();
        double length = Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) vector[i] = (float) (vector[i] / length);
        cache.put(scope, key, vector); return vector;
    }
    private AiServiceException invalidEmbedding() { return new AiServiceException(502, "Local AI returned an invalid embedding. Nothing was indexed."); }
    @Override public String generateChat(String system, String input) { return complete(system, input, false); }
    @Override public String generateJson(String system, String input) { return complete(system, input, true); }
    private String complete(String system, String input, boolean json) {
        if (input == null || input.isBlank() || input.length() > 16000 || (system != null && system.length() > 16000))
            throw new IllegalArgumentException("Question or context is empty or too large");
        JsonNode result = exchange("/chat", Map.of("messages", List.of(Map.of("role", "system", "content", system == null ? "" : system),
                Map.of("role", "user", "content", input)), "json", json), Duration.ofSeconds(190));
        JsonNode choice = result.path("choices").path(0);
        if (!"stop".equals(choice.path("finish_reason").asText()))
            throw new AiServiceException(502, "Local AI did not finish its answer. Try a shorter request.");
        JsonNode content = choice.path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) throw new AiServiceException(502, "Local AI returned no answer.");
        String answer = content.asText();
        if (json) {
            try { if (!mapper.readTree(answer).isObject()) throw new IllegalArgumentException(); }
            catch (Exception error) { throw new AiServiceException(502, "Local AI returned an invalid proposal. Nothing was saved."); }
        }
        return answer;
    }
    @Override public void streamChat(String system, String input, Consumer<String> chunks, Runnable complete) {
        // Buffer complete output so callers can validate grounding before showing it.
        chunks.accept(generateChat(system, input)); complete.run();
    }
    private JsonNode exchange(String route, Map<String, Object> body, Duration timeout) {
        try {
            var request = HttpRequest.newBuilder(origin.resolve(route)).header("Authorization", "Bearer " + token).timeout(timeout);
            if (body == null) request.GET();
            else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                int status = Set.of(400, 408, 413, 429).contains(response.statusCode()) ? response.statusCode() : 503;
                throw new AiServiceException(status, status == 429 ? "Local AI is busy. Try again shortly." : "Local AI could not complete this request. Your records remain available.");
            }
            if (response.body().length() > 100000) throw new AiServiceException(502, "Local AI returned an oversized response.");
            JsonNode parsed = mapper.readTree(response.body());
            if (parsed == null || !parsed.isObject()) throw new AiServiceException(502, "Local AI returned an invalid response.");
            return parsed;
        } catch (AiServiceException error) { throw error; }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AiServiceException(408, "Local AI request cancelled."); }
        catch (Exception error) { throw new AiServiceException(503, "Local AI is unavailable. Your records remain available."); }
    }
}
