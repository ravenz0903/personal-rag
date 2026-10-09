package com.tracker.rag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.rag.domain.*;
import com.tracker.rag.dto.CardAlreadyConfirmedException;
import com.tracker.rag.model.*;
import com.tracker.rag.repository.TaskRepository;
import com.tracker.rag.service.*;
import com.tracker.rag.service.ai.AiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class AssistantActionsIntegrationTest {
    @Autowired CaptureService capture;
    @Autowired TrackerService tracker;
    @Autowired TaskRepository tasks;
    @Autowired ObjectMapper mapper;
    @Autowired RollbackService rollback;
    private final Instant due = Instant.now().plusSeconds(86400);
    private final Instant reminder = Instant.now().plusSeconds(3600);

    @Test void creationIsOnlyAppliedAfterApprovalAndCanBeUndone() {
        long before = tasks.count();
        var card = capture.createStructuredPreview(DomainScope.ACADEMICS, "TASK", Map.of("title", "Reminder test", "dueDate", due.toString(), "remindAt", reminder.toString()));
        assertEquals(before, tasks.count());
        UUID id = (UUID)capture.commitConfirmation(card.confirmationToken());
        tasks.flush();
        assertEquals(reminder, tasks.findById(id).orElseThrow().getRemindAt());
        assertThrows(CardAlreadyConfirmedException.class, () -> capture.commitConfirmation(card.confirmationToken()));
        rollback.rollbackLastMutation(DomainScope.ACADEMICS);
        assertFalse(tasks.existsById(id));
    }
    @Test void approvedEditPreservesIdentityAndUndoRestoresReminder() {
        var create = capture.createStructuredPreview(DomainScope.CAREER, "TASK", Map.of("title", "Interview", "dueDate", due.toString(), "remindAt", reminder.toString()));
        UUID id = (UUID)capture.commitConfirmation(create.confirmationToken()); tasks.flush();
        var edit = capture.createStructuredPreview(DomainScope.CAREER, "TASK_UPDATE", Map.of("taskId", id.toString(), "title", "New interview time", "priority", "HIGH"));
        assertEquals("Interview", tasks.findById(id).orElseThrow().getTitle());
        capture.commitConfirmation(edit.confirmationToken()); tasks.flush();
        Task changed = tasks.findById(id).orElseThrow();
        assertEquals("New interview time", changed.getTitle()); assertNull(changed.getRemindAt()); assertNull(changed.getDueDate());
        rollback.rollbackLastMutation(DomainScope.CAREER); tasks.flush();
        Task restored = tasks.findById(id).orElseThrow();
        assertEquals("Interview", restored.getTitle()); assertEquals(reminder, restored.getRemindAt());
    }
    @Test void rejectsWrongDomainStaleDraftAndPastReminder() {
        Task task = tracker.createTask(DomainScope.ACADEMICS, "Original", TaskPriority.MEDIUM, due); tasks.flush();
        assertThrows(IllegalArgumentException.class, () -> capture.createStructuredPreview(DomainScope.CAREER, "TASK_UPDATE", Map.of("taskId", task.getId().toString(), "title", "Wrong area")));
        var card = capture.createStructuredPreview(DomainScope.ACADEMICS, "TASK_UPDATE", Map.of("taskId", task.getId().toString(), "title", "Stale edit"));
        task.setUpdatedAt(Instant.now().plusSeconds(1)); tasks.saveAndFlush(task);
        assertThrows(IllegalArgumentException.class, () -> capture.commitConfirmation(card.confirmationToken()));
        assertEquals("Original", task.getTitle());
        assertThrows(IllegalArgumentException.class, () -> capture.createStructuredPreview(DomainScope.ACADEMICS, "TASK", Map.of("title", "Past", "remindAt", Instant.now().minusSeconds(1).toString())));
    }
    @Test void assistantUpdatesMergeUnchangedFieldsAndNeverWriteDirectly() throws Exception {
        var create = capture.createStructuredPreview(DomainScope.ACADEMICS, "TASK", Map.of("title", "Revision", "dueDate", due.toString(), "remindAt", reminder.toString()));
        UUID id = (UUID)capture.commitConfirmation(create.confirmationToken()); tasks.flush();
        var fake = new FakeAi("{\"entityType\":\"TASK_UPDATE\",\"payload\":{\"taskId\":\"" + id + "\",\"priority\":\"HIGH\"}}");
        var controller = new AssistantActionController(fake, mapper, tasks, capture);
        ReflectionTestUtils.setField(controller, "timezone", "Asia/Kolkata");
        var card = controller.draft(new AssistantActionController.Request("ACADEMICS", "Make Revision high priority"));
        assertEquals(TaskPriority.MEDIUM, tasks.findById(id).orElseThrow().getPriority());
        assertEquals(reminder.toString(), card.proposedPayload().get("remindAt"));
        capture.commitConfirmation(card.confirmationToken());
        assertEquals(TaskPriority.HIGH, tasks.findById(id).orElseThrow().getPriority());
        assertThrows(IllegalArgumentException.class, () -> controller.draft(new AssistantActionController.Request("PERSONAL", "Private task")));
        assertEquals(1, fake.calls);
    }
    static class FakeAi implements AiClient {
        final String response; int calls;
        FakeAi(String response) { this.response = response; }
        public float[] generateEmbedding(String text, DomainScope scope) { throw new UnsupportedOperationException(); }
        public String generateChat(String system, String input) { calls++; return response; }
        public void streamChat(String system, String input, Consumer<String> consumer, Runnable done) { throw new UnsupportedOperationException(); }
    }
}
