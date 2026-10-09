package com.tracker.rag.service.ai;

import com.fasterxml.jackson.databind.*;
import com.tracker.rag.domain.*;
import com.tracker.rag.service.cache.CaffeineEmbeddingCache;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GeminiClientServiceTest {
    HttpServer server;
    final ObjectMapper mapper = new ObjectMapper();
    final AtomicInteger requests = new AtomicInteger();
    String response;
    int status = 200;
    String requestBody, requestKey;
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestKey = exchange.getRequestHeaders().getFirst("x-goog-api-key");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
    }
    @AfterEach void cleanup() { server.stop(0); }
    GeminiClientService client(int daily) {
        return new GeminiClientService(mapper, new CaffeineEmbeddingCache(10), "test-key",
                "http://127.0.0.1:" + server.getAddress().getPort(), "gemini-2.5-flash", "gemini-embedding-2", false, daily, 20);
    }
    @Test void embeddingsNormalizeAndSeparateQueryDomainAndDocumentCache() throws Exception {
        response = mapper.writeValueAsString(Map.of("embedding", Map.of("values", Collections.nCopies(768, 2.0))));
        GeminiClientService client = client(10);
        float[] vector = client.generateEmbedding("notes", DomainScope.ACADEMICS);
        assertEquals(1.0, Math.sqrt(Arrays.stream(toDouble(vector)).map(x -> x*x).sum()), 0.00001);
        assertEquals("test-key", requestKey);
        assertEquals(768, mapper.readTree(requestBody).path("outputDimensionality").asInt());
        assertTrue(requestBody.contains("title: none | text: notes"));
        client.generateEmbedding("notes", DomainScope.ACADEMICS);
        assertEquals(1, requests.get());
        client.generateQueryEmbedding("notes", DomainScope.ACADEMICS);
        assertTrue(requestBody.contains("task: search result | query: notes"));
        client.generateEmbedding("notes", DomainScope.CAREER);
        assertEquals(3, requests.get());
    }
    @Test void unscopedAndPersonalRequestsNeverContactProvider() {
        GeminiClientService client = client(10);
        assertThrows(UnscopedQueryException.class, () -> client.generateEmbedding("secret", null));
        assertEquals(403, assertThrows(AiServiceException.class, () -> client.generateEmbedding("secret", DomainScope.PERSONAL)).status());
        assertEquals(0, requests.get());
    }
    @Test void providerQuotaFailureNeverCreatesFallbackVectorOrCacheEntry() {
        response = "{\"error\":\"private provider details\"}"; status = 429;
        GeminiClientService client = client(10);
        assertEquals(429, assertThrows(AiServiceException.class, () -> client.generateEmbedding("notes", DomainScope.ACADEMICS)).status());
        assertThrows(AiServiceException.class, () -> client.generateEmbedding("notes", DomainScope.ACADEMICS));
        assertEquals(2, requests.get());
    }
    @Test void invalidVectorsAreRejected() {
        response = "{\"embedding\":{\"values\":[1,2,3]}}";
        assertEquals(502, assertThrows(AiServiceException.class, () -> client(10).generateEmbedding("notes", DomainScope.CAREER)).status());
    }
    @Test void streamsOnlyVisibleTextAndCompletesAfterSuccessfulStream() {
        response = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hidden\",\"thought\":true},{\"text\":\"Hello \"}]}}]}\n\n" +
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"world\"}]}}]}\n\n";
        StringBuilder answer = new StringBuilder(); AtomicInteger completed = new AtomicInteger();
        client(10).streamChat("system", "question", answer::append, completed::incrementAndGet);
        assertEquals("Hello world", answer.toString()); assertEquals(1, completed.get());
    }
    @Test void appDailyQuotaStopsAdditionalCalls() {
        response = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"answer\"}]}}]}";
        GeminiClientService client = client(1);
        assertEquals("answer", client.generateChat("system", "question"));
        assertEquals(429, assertThrows(AiServiceException.class, () -> client.generateChat("system", "another")).status());
        assertEquals(1, requests.get());
    }
    private double[] toDouble(float[] floats) {
        double[] result = new double[floats.length]; for (int i=0;i<floats.length;i++) result[i]=floats[i]; return result;
    }
}
