package com.tracker.rag.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class AppController {
    @Value("${tracker.auth.enabled:true}") boolean auth;
    @Value("${supabase.url:}") String supabaseUrl;
    @Value("${supabase.public-key:}") String publicKey;
    @Value("${ai.personal-enabled:false}") boolean personalAi;
    @Value("${gemini.api-key:}") String apiKey;
    @Value("${ai.enabled:true}") boolean aiEnabled;
    @Value("${ai.provider:gemini}") String provider;
    @org.springframework.beans.factory.annotation.Autowired(required=false) com.tracker.rag.service.ai.LocalAiClient localAi;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.core.env.Environment environment;
    @GetMapping("/api/health") public Map<String, String> health() { return Map.of("status", "ok"); }
    @GetMapping("/api/config") public Map<String, Object> config() {
        Map<String, Object> aiStatus = localAi == null ? Map.of("available", false, "state", "disabled") : localAi.status();
        return Map.of("authEnabled", auth, "supabaseUrl", supabaseUrl, "supabasePublicKey", publicKey,
                "personalAiEnabled", personalAi, "aiEnabled", aiEnabled, "desktopMode", java.util.Arrays.asList(environment.getActiveProfiles()).contains("desktop"),
                "aiConfigured", aiEnabled && (provider.equals("local") ? Boolean.TRUE.equals(aiStatus.get("available")) : !apiKey.isBlank()),
                "aiProvider", provider, "localAiStatus", aiStatus);
    }
}
