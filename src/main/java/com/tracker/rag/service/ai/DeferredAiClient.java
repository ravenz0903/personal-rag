package com.tracker.rag.service.ai;

import com.tracker.rag.domain.DomainScope;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.function.Consumer;

/** Desktop mode cannot call a provider even if an old environment contains a key. */
@Service @Primary @Profile("desktop")
public class DeferredAiClient implements AiClient {
    public String embeddingSpace() { return "ai-postponed"; }
    private AiServiceException unavailable() { return new AiServiceException(503, "AI is postponed. Your local records work without it."); }
    public float[] generateEmbedding(String text, DomainScope domain) { throw unavailable(); }
    public String generateChat(String system, String input) { throw unavailable(); }
    public String generateJson(String system, String input) { throw unavailable(); }
    public void streamChat(String system, String input, Consumer<String> chunks, Runnable complete) { throw unavailable(); }
}
