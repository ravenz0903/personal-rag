package com.tracker.rag.service;

import com.tracker.rag.domain.*;
import com.tracker.rag.model.*;
import com.tracker.rag.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class WorkspaceWorkflowIntegrationTest {
    @Autowired WorkspaceService workspace;
    @Autowired CaptureService capture;
    @Autowired TrackerService tracker;
    @Autowired RollbackService rollback;
    @Autowired AssessmentRepository assessments;
    @Autowired TaskRepository tasks;
    @Autowired HabitLogRepository logs;
    @Autowired EventLogService events;
    @Autowired DocumentRecordRepository documents;
    @Autowired com.tracker.rag.repository.vector.VectorStore vectors;
    @Test void incompatibleEmbeddingSpacesFailBeforeAnyProviderCallAndStayDomainScoped() {
        DocumentRecord document = new DocumentRecord(DomainScope.ACADEMICS, "old.md", "old.md", "text/markdown", "a".repeat(64), 10);
        document.setChunkCount(1); document.setEmbeddingModelId("old-model"); documents.save(document);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var ai = new com.tracker.rag.service.ai.AiClient() {
            public String embeddingSpace() { return "new-model"; }
            public float[] generateEmbedding(String text, DomainScope domain) { calls.incrementAndGet(); float[] vector = new float[768]; vector[0] = 1; return vector; }
            public String generateChat(String system, String input) { throw new AssertionError("Unexpected chat"); }
            public void streamChat(String system, String input, java.util.function.Consumer<String> chunk, Runnable done) { throw new AssertionError("Unexpected chat"); }
        };
        HybridRetrieverService retriever = new HybridRetrieverService(vectors, ai);
        org.springframework.test.util.ReflectionTestUtils.setField(retriever, "documents", documents);
        assertEquals(409, assertThrows(com.tracker.rag.service.ai.AiServiceException.class,
                () -> retriever.search("notes", DomainScope.ACADEMICS, 5)).status());
        assertEquals(0, calls.get());
        retriever.search("notes", DomainScope.CAREER, 5);
        assertEquals(1, calls.get());
    }
    @Test void confirmedAssessmentPersistsAndItsScoreCanBeUndone() {
        Course course = workspace.createCourse("T" + UUID.randomUUID().toString().substring(0,8), "Algorithms", "Fall", new BigDecimal("90"));
        workspace.createCategory(course.getId(), "Homework", new BigDecimal("100"), 0);
        var card = capture.createStructuredPreview(DomainScope.ACADEMICS, "ASSESSMENT", Map.of("title","Homework 1", "courseCode",course.getCode(),"categoryName","Homework","maxScore",20));
        assertTrue(assessments.findByCourseId(course.getId()).isEmpty());
        capture.commitConfirmation(card.confirmationToken());
        Assessment assessment = assessments.findByCourseId(course.getId()).getFirst();
        workspace.scoreAssessment(assessment.getId(), new BigDecimal("18"));
        var courseView = workspace.courseViews().stream().filter(c -> c.id().equals(course.getId())).findFirst().orElseThrow();
        assertEquals(0, courseView.grade().currentGrade().compareTo(new BigDecimal("90")));
        var scoreEvent = events.getRecentEventsByDomain(DomainScope.ACADEMICS, 20).stream()
                .filter(e -> e.getAction() == ActionType.UPDATE && e.getEntityId().equals(assessment.getId())).findFirst().orElseThrow();
        rollback.rollbackEvent(scoreEvent.getId());
        assertNull(assessments.findById(assessment.getId()).orElseThrow().getEarnedScore());
    }
    @Test void taskToggleUndoPreservesIdentityAndHabitLogsAreIdempotentAndUndoable() {
        Task task = tracker.createTask(DomainScope.CAREER, "Apply", TaskPriority.HIGH, null);
        tracker.toggleTaskStatus(task.getId());
        var toggle = events.getRecentEventsByDomain(DomainScope.CAREER, 20).stream()
                .filter(e -> e.getAction() == ActionType.UPDATE && e.getEntityId().equals(task.getId())).findFirst().orElseThrow();
        rollback.rollbackEvent(toggle.getId());
        assertEquals(TaskStatus.PENDING, tasks.findById(task.getId()).orElseThrow().getStatus());
        var card = capture.createStructuredPreview(DomainScope.PERSONAL,"HABIT",Map.of("name","Read", "timezone","Asia/Kolkata"));
        capture.commitConfirmation(card.confirmationToken());
        var habit = tracker.getHabitsWithStreaks(DomainScope.PERSONAL, java.time.ZoneId.of("Asia/Kolkata")).stream().filter(h -> h.habit().getName().equals("Read")).findFirst().orElseThrow().habit();
        HabitLog log = tracker.logHabit(habit.getId(), LocalDate.now(habit.getZoneId()));
        assertEquals(log.getId(), tracker.logHabit(habit.getId(), log.getLogDate()).getId());
        var event = events.getRecentEventsByDomain(DomainScope.PERSONAL, 20).stream().filter(e -> e.getEntityType() == EntityType.HABIT_LOG && e.getEntityId().equals(log.getId())).findFirst().orElseThrow();
        rollback.rollbackEvent(event.getId());
        assertFalse(logs.existsById(log.getId()));
    }
}
