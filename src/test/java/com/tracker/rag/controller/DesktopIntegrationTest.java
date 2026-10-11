package com.tracker.rag.controller;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.model.*;
import com.tracker.rag.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Path;
import java.time.Instant;
import java.io.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"tracker.desktop.token=0123456789abcdef0123456789abcdef", "spring.jpa.hibernate.ddl-auto=update"})
@ActiveProfiles("desktop") @AutoConfigureMockMvc
class DesktopIntegrationTest {
    static final String TOKEN = "0123456789abcdef0123456789abcdef";
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + directory.resolve("tracker").toString().replace('\\', '/') + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE");
        registry.add("tracker.documents.root", () -> directory.resolve("documents").toString());
    }
    @Autowired MockMvc mvc;
    @Autowired TaskRepository tasks;
    @Autowired DocumentRecordRepository documents;
    @Autowired DesktopController desktop;
    @Test void apiRequiresLaunchSecretAndAiIsDisabled() throws Exception {
        mvc.perform(get("/api/config")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/config").header("X-Folio-Desktop-Token", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/config").header("X-Folio-Desktop-Token", TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.desktopMode").value(true)).andExpect(jsonPath("$.aiEnabled").value(false))
                .andExpect(jsonPath("$.aiProvider").value("disabled"))
                .andExpect(jsonPath("$.localAiStatus.state").value("disabled"))
                .andExpect(jsonPath("$.aiConfigured").value(false))
                .andExpect(jsonPath("$.localAiStatus.key").doesNotExist());
        mvc.perform(post("/api/assistant/actions").header("X-Folio-Desktop-Token", TOKEN).contentType("application/json").content("{}"))
                .andExpect(status().isServiceUnavailable());
    }
    @Test void academicUploadStoresOriginalWithoutEmbeddingKey() throws Exception {
        mvc.perform(post("/api/ingest/markdown").header("X-Folio-Desktop-Token", TOKEN).contentType("application/json")
                .content("{\"path\":\"desktop-test.md\",\"domain\":\"ACADEMICS\",\"content\":\"# Offline notes\\nNo AI is needed.\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.chunksCreated").value(0));
        DocumentRecord record = documents.findByFilePathAndDomain("desktop-test.md", DomainScope.ACADEMICS).orElseThrow();
        assertNotNull(record.getStoragePath()); assertNull(record.getEmbeddingModelId());
        mvc.perform(get("/api/ingest/documents/" + record.getId() + "/download?domain=ACADEMICS").header("X-Folio-Desktop-Token", TOKEN))
                .andExpect(status().isOk()).andExpect(content().string("# Offline notes\nNo AI is needed."));
    }
    @Test void reminderAcknowledgementSurvivesPollingAndReschedulingCreatesNewOccurrence() {
        Task task = new Task(DomainScope.PERSONAL, "Desktop reminder", TaskPriority.MEDIUM, Instant.now().plusSeconds(300));
        task.setRemindAt(Instant.now().minusSeconds(5).truncatedTo(java.time.temporal.ChronoUnit.MICROS)); task = tasks.saveAndFlush(task);
        var reminder = new DesktopController.Reminder(task.getId(), task.getRemindAt());
        assertTrue(desktop.due().contains(reminder)); desktop.ack(reminder); assertFalse(desktop.due().contains(reminder));
        task.setRemindAt(Instant.now().minusSeconds(1)); task = tasks.saveAndFlush(task);
        assertTrue(desktop.due().stream().anyMatch(r -> r.taskId().equals(reminder.taskId())));
        task.setStatus(TaskStatus.COMPLETED); tasks.saveAndFlush(task);
        assertFalse(desktop.due().stream().anyMatch(r -> r.taskId().equals(reminder.taskId())));
    }
    @Test void backupContainsRestorableH2Archive() throws Exception {
        byte[] backup = mvc.perform(get("/api/desktop/backup").header("X-Folio-Desktop-Token", TOKEN)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        boolean database = false, restore = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(backup))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (entry.getName().equals("database.zip")) {
                    try (ZipInputStream nested = new ZipInputStream(new ByteArrayInputStream(zip.readAllBytes()))) {
                        assertNotNull(nested.getNextEntry()); database = true;
                    }
                }
                if (entry.getName().equals("RESTORE.txt")) restore = true;
            }
        }
        assertTrue(database); assertTrue(restore);
    }
}
