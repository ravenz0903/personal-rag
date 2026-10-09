package com.tracker.rag.service.ai;

import com.tracker.rag.domain.DomainScope;
import java.util.function.Consumer;

/** Provider boundary: tracker arithmetic and mutations never belong to the model. */
public interface AiClient {
    default String embeddingSpace() { return "legacy"; }
    float[] generateEmbedding(String text, DomainScope domain);
    default float[] generateQueryEmbedding(String text, DomainScope domain) {
        return generateEmbedding(text, domain);
    }
    String generateChat(String systemPrompt, String userMessage);
    default String generateJson(String systemPrompt, String userMessage) {
        return generateChat(systemPrompt, userMessage);
    }
    void streamChat(String systemPrompt, String userMessage, Consumer<String> chunkConsumer, Runnable onComplete);
}
