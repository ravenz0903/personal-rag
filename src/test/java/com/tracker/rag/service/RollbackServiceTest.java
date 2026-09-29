package com.tracker.rag.service;

import com.tracker.rag.domain.ActionType;
import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.domain.EntityType;
import com.tracker.rag.domain.EventAlreadyRolledBackException;
import com.tracker.rag.domain.EventNotFoundException;
import com.tracker.rag.domain.NoMutationsToRollbackException;
import com.tracker.rag.domain.UnscopedQueryException;
import com.tracker.rag.model.EventLogEntry;
import com.tracker.rag.repository.EventLogRepository;
import com.tracker.rag.service.rollback.RollbackDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RollbackServiceTest {

    @Mock
    private EventLogRepository eventLogRepository;

    private TestRollbackDispatcher rollbackDispatcher;
    private RollbackService rollbackService;

    static class TestRollbackDispatcher implements RollbackDispatcher {
        EntityType lastDeletedType;
        UUID lastDeletedId;
        EntityType lastRestoredType;
        UUID lastRestoredId;
        String lastRestoredPayload;

        @Override
        public void executeDelete(EntityType entityType, UUID entityId) {
            this.lastDeletedType = entityType;
            this.lastDeletedId = entityId;
        }

        @Override
        public void executeRestore(EntityType entityType, UUID entityId, String jsonPayload) {
            this.lastRestoredType = entityType;
            this.lastRestoredId = entityId;
            this.lastRestoredPayload = jsonPayload;
        }
    }

    @BeforeEach
    void setUp() {
        rollbackDispatcher = new TestRollbackDispatcher();
        rollbackService = new RollbackService(eventLogRepository, rollbackDispatcher);
    }

    @Test
    @DisplayName("Rollback CREATE: executes delete and appends ROLLBACK event")
    void testRollbackCreateMutation() {
        UUID entityId = UUID.randomUUID();
        UUID targetEventId = UUID.randomUUID();
        EventLogEntry createEvent = new EventLogEntry(
            targetEventId,
            DomainScope.ACADEMICS,
            EntityType.TASK,
            entityId,
            ActionType.CREATE,
            Instant.now(),
            null,
            "{\"title\":\"Test Task\"}"
        );

        when(eventLogRepository.findFirstByDomainAndUndoneFalseOrderByTimestampDesc(DomainScope.ACADEMICS))
            .thenReturn(Optional.of(createEvent));
        when(eventLogRepository.save(any(EventLogEntry.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        EventLogEntry rollbackEvent = rollbackService.rollbackLastMutation(DomainScope.ACADEMICS);

        assertThat(rollbackDispatcher.lastDeletedType).isEqualTo(EntityType.TASK);
        assertThat(rollbackDispatcher.lastDeletedId).isEqualTo(entityId);
        assertThat(createEvent.isUndone()).isTrue();
        assertThat(createEvent.getUndoneByEventId()).isEqualTo(rollbackEvent.getId());
        assertThat(rollbackEvent.getAction()).isEqualTo(ActionType.ROLLBACK);
        assertThat(rollbackEvent.getDomain()).isEqualTo(DomainScope.ACADEMICS);
        assertThat(rollbackEvent.getPayloadBefore()).isEqualTo("{\"title\":\"Test Task\"}");
        assertThat(rollbackEvent.getPayloadAfter()).isNull();
    }

    @Test
    @DisplayName("Rollback UPDATE: executes restore with payloadBefore")
    void testRollbackUpdateMutation() {
        UUID entityId = UUID.randomUUID();
        EventLogEntry updateEvent = new EventLogEntry(
            UUID.randomUUID(),
            DomainScope.CAREER,
            EntityType.CAREER_APPLICATION,
            entityId,
            ActionType.UPDATE,
            Instant.now(),
            "{\"company\":\"Stripe\",\"status\":\"APPLIED\"}",
            "{\"company\":\"Stripe\",\"status\":\"REJECTED\"}"
        );

        when(eventLogRepository.findFirstByDomainAndUndoneFalseOrderByTimestampDesc(DomainScope.CAREER))
            .thenReturn(Optional.of(updateEvent));
        when(eventLogRepository.save(any(EventLogEntry.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        EventLogEntry rollbackEvent = rollbackService.rollbackLastMutation(DomainScope.CAREER);

        assertThat(rollbackDispatcher.lastRestoredType).isEqualTo(EntityType.CAREER_APPLICATION);
        assertThat(rollbackDispatcher.lastRestoredId).isEqualTo(entityId);
        assertThat(rollbackDispatcher.lastRestoredPayload).isEqualTo("{\"company\":\"Stripe\",\"status\":\"APPLIED\"}");
        assertThat(updateEvent.isUndone()).isTrue();
        assertThat(rollbackEvent.getAction()).isEqualTo(ActionType.ROLLBACK);
        assertThat(rollbackEvent.getPayloadAfter()).isEqualTo("{\"company\":\"Stripe\",\"status\":\"APPLIED\"}");
    }

    @Test
    @DisplayName("Rollback DELETE: executes restore with payloadBefore (re-insertion)")
    void testRollbackDeleteMutation() {
        UUID entityId = UUID.randomUUID();
        EventLogEntry deleteEvent = new EventLogEntry(
            UUID.randomUUID(),
            DomainScope.PERSONAL,
            EntityType.HABIT,
            entityId,
            ActionType.DELETE,
            Instant.now(),
            "{\"name\":\"Morning Meditation\"}",
            null
        );

        when(eventLogRepository.findFirstByDomainAndUndoneFalseOrderByTimestampDesc(DomainScope.PERSONAL))
            .thenReturn(Optional.of(deleteEvent));
        when(eventLogRepository.save(any(EventLogEntry.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        EventLogEntry rollbackEvent = rollbackService.rollbackLastMutation(DomainScope.PERSONAL);

        assertThat(rollbackDispatcher.lastRestoredType).isEqualTo(EntityType.HABIT);
        assertThat(rollbackDispatcher.lastRestoredId).isEqualTo(entityId);
        assertThat(rollbackDispatcher.lastRestoredPayload).isEqualTo("{\"name\":\"Morning Meditation\"}");
        assertThat(deleteEvent.isUndone()).isTrue();
        assertThat(rollbackEvent.getAction()).isEqualTo(ActionType.ROLLBACK);
        assertThat(rollbackEvent.getPayloadAfter()).isEqualTo("{\"name\":\"Morning Meditation\"}");
    }

    @Test
    @DisplayName("Attempting to rollback an already undone event throws EventAlreadyRolledBackException")
    void testRollbackAlreadyUndoneEvent() {
        UUID eventId = UUID.randomUUID();
        UUID priorRollbackId = UUID.randomUUID();
        EventLogEntry event = new EventLogEntry(
            eventId,
            DomainScope.ACADEMICS,
            EntityType.TASK,
            UUID.randomUUID(),
            ActionType.CREATE,
            Instant.now(),
            null,
            "{}"
        );
        event.markUndoneBy(priorRollbackId);

        when(eventLogRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> rollbackService.rollbackEvent(eventId))
            .isInstanceOf(EventAlreadyRolledBackException.class)
            .hasMessageContaining(eventId.toString());
    }

    @Test
    @DisplayName("Rollback when no active mutations exist throws NoMutationsToRollbackException")
    void testRollbackWhenNoMutationsExist() {
        when(eventLogRepository.findFirstByDomainAndUndoneFalseOrderByTimestampDesc(DomainScope.PERSONAL))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> rollbackService.rollbackLastMutation(DomainScope.PERSONAL))
            .isInstanceOf(NoMutationsToRollbackException.class)
            .hasMessageContaining("PERSONAL");
    }

    @Test
    @DisplayName("Rollback with null domain throws UnscopedQueryException")
    void testRollbackNullDomainThrows() {
        assertThatThrownBy(() -> rollbackService.rollbackLastMutation(null))
            .isInstanceOf(UnscopedQueryException.class);
    }

    @Test
    @DisplayName("Rollback non-existent eventId throws EventNotFoundException")
    void testRollbackNonExistentEventThrows() {
        UUID unknownId = UUID.randomUUID();
        when(eventLogRepository.findByIdForUpdate(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> rollbackService.rollbackEvent(unknownId))
            .isInstanceOf(EventNotFoundException.class);
    }
}
