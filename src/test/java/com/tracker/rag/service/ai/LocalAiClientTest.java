package com.tracker.rag.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.rag.domain.*;
import com.tracker.rag.service.cache.CaffeineEmbeddingCache;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalAiClientTest {
    HttpServer server;
    final ObjectMapper mapper = new ObjectMapper();
    String response = "{}", body, authorization;
    int requests, status = 200;
    static final String TOKEN = "a".repeat(64);
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests++; body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    String origin() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    LocalAiClient client() { return new LocalAiClient(mapper, new CaffeineEmbeddingCache(20), origin(), TOKEN, "nomic-768:q4:pinned:search-v1"); }
    @Test void rejectsExternalEndpointsAndMissingPrivateCredentials() {
        for (String endpoint : List.of("https://example.com", "http://localhost:123", origin()+"/extra", origin()+"?key=x"))
            assertThrows(IllegalArgumentException.class, () -> new LocalAiClient(mapper, new CaffeineEmbeddingCache(20), endpoint, TOKEN, "pinned-space-123"));
        assertThrows(IllegalArgumentException.class, () -> new LocalAiClient(mapper, new CaffeineEmbeddingCache(20), origin(), "", "pinned-space-123"));
        assertEquals(0, requests);
    }
    @Test void scopedEmbeddingsSeparateQueriesAndDocumentsAndNormalize() throws Exception {
        response = mapper.writeValueAsString(Map.of("data", List.of(Map.of("embedding", Collections.nCopies(768, 2)))));
        var client = client();
        assertThrows(UnscopedQueryException.class, () -> client.generateEmbedding("notes", null)); assertEquals(0, requests);
        float[] vector = client.generateEmbedding("notes", DomainScope.ACADEMICS);
        double norm = 0; for (float value : vector) norm += value*value; assertEquals(1.0, norm, .00001);
        assertEquals("Bearer " + TOKEN, authorization); assertTrue(body.contains("search_document: notes"));
        client.generateEmbedding("notes", DomainScope.ACADEMICS); assertEquals(1, requests);
        client.generateQueryEmbedding("notes", DomainScope.ACADEMICS); assertTrue(body.contains("search_query: notes"));
        client.generateEmbedding("notes", DomainScope.PERSONAL); assertEquals(3, requests);
    }
    @Test void invalidEmbeddingsNeverEnterTheCache() throws Exception {
        var client = client();
        for (Object values : List.of(List.of(1, 2), Collections.nCopies(768, "2"), Collections.nCopies(768, 0))) {
            response = mapper.writeValueAsString(Map.of("data", List.of(Map.of("embedding", values))));
            assertEquals(502, assertThrows(AiServiceException.class, () -> client.generateEmbedding("same text", DomainScope.CAREER)).status());
        }
        assertEquals(3, requests);
    }
    @Test void truncatedOrMalformedProposalsAndRawRuntimeErrorsAreRejected() throws Exception {
        var client = client();
        response = mapper.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "length", "message", Map.of("content", "{}")))));
        assertEquals(502, assertThrows(AiServiceException.class, () -> client.generateJson("system", "request")).status());
        response = mapper.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", "not JSON")))));
        assertThrows(AiServiceException.class, () -> client.generateJson("system", "request"));
        status = 500; response = "{\"error\":\"private paths and keys\"}";
        var error = assertThrows(AiServiceException.class, () -> client.generateChat("system", "request"));
        assertEquals(503, error.status()); assertFalse(error.getMessage().contains("private paths"));
    }
    @Test void statusExposesNoSecretsAndCompleteJsonIsPassedIntact() throws Exception {
        var client = client(); response = "{\"available\":true,\"state\":\"loading\",\"backend\":\"gpu\",\"key\":\"secret\"}";
        assertEquals(Map.of("available", true, "state", "loading", "backend", "gpu"), client.status());
        response = mapper.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", "{\"action\":\"clarify\"}")))));
        assertEquals("{\"action\":\"clarify\"}", client.generateJson("system", "request"));
        assertTrue(mapper.readTree(body).path("json").asBoolean());
    }
}
