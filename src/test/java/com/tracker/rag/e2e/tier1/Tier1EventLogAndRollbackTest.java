package com.tracker.rag.e2e.tier1;

import com.tracker.rag.e2e.contract.*;
import com.tracker.rag.e2e.stub.InMemoryEventLogAndRollbackServiceStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Tier 1: Append-Only Event Log and State Rollback Tests (F11, F12)")
public class Tier1EventLogAndRollbackTest {

    private InMemoryEventLogAndRollbackServiceStub eventLogService;

    @BeforeEach
    void setUp() {
        eventLogService = new InMemoryEventLogAndRollbackServiceStub();
    }

    @Nested
    @DisplayName("F11: Append-Only Event Log Tests")
    class EventLogTests {

        @Test
        @DisplayName("TC-F11-01: Record mutation appends entry with before/after state")
        void testRecordMutation() {
            UUID entityId = UUID.randomUUID();
            EventLogRecord entry = eventLogService.recordMutation(
                    DomainScope.ACADEMICS,
                    EntityType.TASK,
                    entityId,
                    ActionType.CREATE,
                    null,
                    "{\"title\":\"Homework 1\"}"
            );

            assertNotNull(entry.id());
            assertEquals(DomainScope.ACADEMICS, entry.domain());
            assertEquals(EntityType.TASK, entry.entityType());
            assertEquals(entityId, entry.entityId());
            assertEquals(ActionType.CREATE, entry.action());
            assertNull(entry.payloadBefore());
            assertEquals("{\"title\":\"Homework 1\"}", entry.payloadAfter());
            assertFalse(entry.undone());
            assertNull(entry.undoneByEventId());
        }

        @Test
        @DisplayName("TC-F11-02: Query recent events scoped strictly by domain")
        void testQueryRecentEventsScoped() {
            UUID t1 = UUID.randomUUID();
            UUID t2 = UUID.randomUUID();
            UUID t3 = UUID.randomUUID();

            eventLogService.recordMutation(DomainScope.ACADEMICS, EntityType.TASK, t1, ActionType.CREATE, null, "A1");
            eventLogService.recordMutation(DomainScope.PERSONAL, EntityType.HABIT, t2, ActionType.CREATE, null, "P1");
            eventLogService.recordMutation(DomainScope.ACADEMICS, EntityType.TASK, t3, ActionType.CREATE, null, "A2");

            List<EventLogRecord> academicEvents = eventLogService.getRecentEvents(DomainScope.ACADEMICS, 10);
            assertEquals(2, academicEvents.size());
            assertTrue(academicEvents.stream().allMatch(e -> e.domain() == DomainScope.ACADEMICS));

            List<EventLogRecord> personalEvents = eventLogService.getRecentEvents(DomainScope.PERSONAL, 10);
            assertEquals(1, personalEvents.size());
            assertEquals(DomainScope.PERSONAL, personalEvents.get(0).domain());
        }

        @Test
        @DisplayName("TC-F11-03: Event log enforces fail-closed validation on null domain")
        void testEventLogNullDomainFailsClosed() {
            assertThrows(UnscopedQueryException.class, () ->
                    eventLogService.recordMutation(null, EntityType.TASK, UUID.randomUUID(), ActionType.CREATE, null, "{}"));

            assertThrows(UnscopedQueryException.class, () ->
                    eventLogService.getRecentEvents(null, 10));
        }

        @Test
        @DisplayName("TC-F11-04: ActionType inverse mappings")
        void testActionTypeInverses() {
            assertEquals(ActionType.DELETE, ActionType.CREATE.inverse());
            assertEquals(ActionType.CREATE, ActionType.DELETE.inverse());
            assertEquals(ActionType.UPDATE, ActionType.UPDATE.inverse());
            assertThrows(UnsupportedOperationException.class, ActionType.ROLLBACK::inverse);
        }

        @Test
        @DisplayName("TC-F11-05: Append-only invariant: total events count monotonically increases")
        void testAppendOnlyImmutability() {
            assertEquals(0, eventLogService.totalEventsCount());

            UUID id = UUID.randomUUID();
            eventLogService.recordMutation(DomainScope.CAREER, EntityType.CAREER_APPLICATION, id, ActionType.CREATE, null, "{}");
            assertEquals(1, eventLogService.totalEventsCount());

            eventLogService.recordMutation(DomainScope.CAREER, EntityType.CAREER_APPLICATION, id, ActionType.UPDATE, "{}", "{\"status\":\"APPLIED\"}");
            assertEquals(2, eventLogService.totalEventsCount());
        }
    }

    @Nested
    @DisplayName("F12: State Rollback (Undo) Engine Tests")
    class StateRollbackTests {

        @Test
        @DisplayName("TC-F12-01: Rollback last CREATE mutation removes entity and appends ROLLBACK event")
        void testRollbackLastCreate() {
            UUID entityId = UUID.randomUUID();
            eventLogService.recordMutation(
                    DomainScope.ACADEMICS, EntityType.TASK, entityId, ActionType.CREATE, null, "{\"title\":\"Lab 1\"}"
            );
            assertTrue(eventLogService.isEntityPresent(entityId));

            EventLogRecord rollbackEntry = eventLogService.rollbackLastMutation(DomainScope.ACADEMICS);

            assertNotNull(rollbackEntry);
            assertEquals(ActionType.ROLLBACK, rollbackEntry.action());
            assertEquals(entityId, rollbackEntry.entityId());
            assertFalse(eventLogService.isEntityPresent(entityId), "Entity should be removed after undoing CREATE");
            assertEquals(2, eventLogService.totalEventsCount(), "Rollback appends new event to immutable journal");
        }

        @Test
        @DisplayName("TC-F12-02: Rollback last UPDATE mutation restores previous payload")
        void testRollbackLastUpdate() {
            UUID entityId = UUID.randomUUID();
            eventLogService.recordMutation(
                    DomainScope.CAREER, EntityType.CAREER_APPLICATION, entityId, ActionType.CREATE, null, "{\"v\":1}"
            );
            eventLogService.recordMutation(
                    DomainScope.CAREER, EntityType.CAREER_APPLICATION, entityId, ActionType.UPDATE, "{\"v\":1}", "{\"v\":2}"
            );

            assertEquals("{\"v\":2}", eventLogService.getEntityPayload(entityId));

            eventLogService.rollbackLastMutation(DomainScope.CAREER);

            assertEquals("{\"v\":1}", eventLogService.getEntityPayload(entityId), "Entity should be restored to prior state");
            assertEquals(3, eventLogService.totalEventsCount());
        }

        @Test
        @DisplayName("TC-F12-03: Rollback domain isolation: rollback in ACADEMICS never rolls back PERSONAL")
        void testRollbackDomainIsolation() {
            UUID pId = UUID.randomUUID();
            eventLogService.recordMutation(DomainScope.PERSONAL, EntityType.TASK, pId, ActionType.CREATE, null, "P");

            assertThrows(NoMutationsToRollbackException.class, () ->
                    eventLogService.rollbackLastMutation(DomainScope.ACADEMICS),
                    "Rollback in Academics must not touch Personal events"
            );
            assertTrue(eventLogService.isEntityPresent(pId));
        }

        @Test
        @DisplayName("TC-F12-04: Cannot rollback an already rolled back event")
        void testCannotRollbackAlreadyRolledBackEvent() {
            UUID entityId = UUID.randomUUID();
            EventLogRecord created = eventLogService.recordMutation(
                    DomainScope.ACADEMICS, EntityType.TASK, entityId, ActionType.CREATE, null, "{}"
            );

            eventLogService.rollbackEvent(created.id());

            assertThrows(EventAlreadyRolledBackException.class, () ->
                    eventLogService.rollbackEvent(created.id())
            );
        }

        @Test
        @DisplayName("TC-F12-05: Rollback when no active mutations exist throws NoMutationsToRollbackException")
        void testRollbackNoMutationsThrows() {
            assertThrows(NoMutationsToRollbackException.class, () ->
                    eventLogService.rollbackLastMutation(DomainScope.ACADEMICS));
        }
    }
}
