package com.tracker.rag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.rag.service.OllamaClientService;
import com.tracker.rag.service.ai.AiClient;
import com.tracker.rag.service.cache.EmbeddingCache;
import org.springframework.context.annotation.*;

/** Test source only: this adapter is never packaged into the deployed application. */
@Configuration
@Profile("test & !cloud")
public class AiTestConfig {
    @Bean AiClient testClient(ObjectMapper mapper, EmbeddingCache cache) { return new OllamaClientService(mapper, cache); }
}
