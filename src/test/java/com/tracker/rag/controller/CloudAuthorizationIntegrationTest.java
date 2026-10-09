package com.tracker.rag.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"tracker.auth.enabled=true", "supabase.url=https://example.supabase.co",
        "tracker.auth.owner-id=122e4567-e89b-12d3-a456-426614174000"})
@AutoConfigureMockMvc
class CloudAuthorizationIntegrationTest {
    @Autowired MockMvc mvc;
    @Test void publicConfigurationIsAccessibleButAllDataAndMutationsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/config")).andExpect(status().isOk()).andExpect(jsonPath("$.authEnabled").value(true));
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(get("/api/tracker/tasks?domain=ACADEMICS")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ingest/documents?domain=PERSONAL")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/capture/structured").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/workspace/courses").header("Authorization", "Bearer invalid.token.value")).andExpect(status().isUnauthorized());
    }
}
