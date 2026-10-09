package com.tracker.rag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.dto.ConfirmationCard;
import com.tracker.rag.model.Task;
import com.tracker.rag.model.TaskPriority;
import com.tracker.rag.service.CaptureService;
import com.tracker.rag.service.TrackerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Production Endpoints Integration Tests")
public class ProductionEndpointsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TrackerService trackerService;

    @Autowired
    private CaptureService captureService;

    @Test
    @DisplayName("GET /api/tracker/today returns aggregated dashboard for specified domains")
    void testGetTodayDashboard() throws Exception {
        trackerService.createTask(DomainScope.ACADEMICS, "Read Chapter 4", TaskPriority.HIGH, Instant.now().plusSeconds(3600));

        mockMvc.perform(get("/api/tracker/today?domains=ACADEMICS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.tasks[0].domain", is("ACADEMICS")));
    }

    @Test
    @DisplayName("POST /api/tracker/tasks creates a task and supports toggling completion")
    void testCreateAndToggleTask() throws Exception {
        TrackerController.CreateTaskRequest req = new TrackerController.CreateTaskRequest(
                "CAREER",
                "Apply to Anthropic",
                "HIGH",
                Instant.now().plusSeconds(86400).toString()
        );

        String responseJson = mockMvc.perform(post("/api/tracker/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("Apply to Anthropic")))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andReturn().getResponse().getContentAsString();

        Task createdTask = objectMapper.readValue(responseJson, Task.class);

        // Toggle task status
        mockMvc.perform(post("/api/tracker/tasks/" + createdTask.getId() + "/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")));
    }

    @Test
    @DisplayName("POST /api/capture/preview and /api/capture/commit execute human-in-the-loop flow")
    void testCapturePreviewAndCommit() throws Exception {
        CaptureController.PreviewRequest req = new CaptureController.PreviewRequest(
                "schedule doctor appointment next Tuesday",
                "PERSONAL"
        );

        String previewJson = mockMvc.perform(post("/api/capture/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityType", is("TASK")))
                .andExpect(jsonPath("$.targetDomain", is("PERSONAL")))
                .andExpect(jsonPath("$.confirmationToken", notNullValue()))
                .andReturn().getResponse().getContentAsString();

        ConfirmationCard card = objectMapper.readValue(previewJson, ConfirmationCard.class);

        // Commit card
        mockMvc.perform(post("/api/capture/commit/" + card.confirmationToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMMITTED")))
                .andExpect(jsonPath("$.entityId", notNullValue()));
    }

    @Test
    @DisplayName("POST /api/ingest/markdown ingests document and persists metadata")
    void testIngestMarkdown() throws Exception {
        IngestionController.MarkdownIngestRequest req = new IngestionController.MarkdownIngestRequest(
                "docs/syllabus.md",
                "ACADEMICS",
                "# CS101 Introduction\n\nCourse schedule and grading weights."
        );

        mockMvc.perform(post("/api/ingest/markdown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filePath", is("syllabus.md")))
                .andExpect(jsonPath("$.chunksCreated", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.status", is("CREATED")));

        // Verify document is in document registry
        mockMvc.perform(get("/api/ingest/documents?domain=ACADEMICS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    @DisplayName("POST /api/chat/ask enforces refusal when context is missing")
    void testChatAskRefusalWhenNotesEmpty() throws Exception {
        ChatController.AskRequest req = new ChatController.AskRequest(
                "What is the final exam date for History 200?",
                "CAREER"
        );

        mockMvc.perform(post("/api/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", containsString("not found in your notes")));
    }
}
