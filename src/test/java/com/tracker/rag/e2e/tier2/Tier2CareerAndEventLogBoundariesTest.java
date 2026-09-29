package com.tracker.rag.e2e.tier2;

import com.tracker.rag.e2e.contract.*;
import com.tracker.rag.e2e.stub.InMemoryEventLogAndRollbackServiceStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Tier 2: Boundary & Corner Cases - Career Pipeline & Event Log Rollback (F10-F12)")
public class Tier2CareerAndEventLogBoundariesTest {

    private InMemoryEventLogAndRollbackServiceStub eventLogService;

    @BeforeEach
    void setUp() {
        eventLogService = new InMemoryEventLogAndRollbackServiceStub();
    }

    @Nested
    @DisplayName("Career Pipeline State Machine Boundary Tests")
    class CareerPipelineBoundaries {

        @Test
        @DisplayName("BC-F10-01: Terminal REJECTED cannot transition to OFFER")
        void testRejectedToOfferRejected() {
            CareerApplicationRecord app = CareerApplicationRecord.create("Amazon", "SWE", CareerStatus.APPLIED)
                    .withStatus(CareerStatus.REJECTED);

            assertThrows(InvalidStateTransitionException.class, () -> app.withStatus(CareerStatus.OFFER));
        }

        @Test
        @DisplayName("BC-F10-02: Terminal WITHDRAWN cannot transition to APPLIED")
        void testWithdrawnToAppliedRejected() {
            CareerApplicationRecord app = CareerApplicationRecord.create("Google", "SWE", CareerStatus.WISHLIST)
                    .withStatus(CareerStatus.WITHDRAWN);

            assertThrows(InvalidStateTransitionException.class, () -> app.withStatus(CareerStatus.APPLIED));
        }

        @Test
        @DisplayName("BC-F10-03: Invalid direct jump from WISHLIST directly to OFFER")
        void testWishlistDirectToOfferRejected() {
            CareerApplicationRecord app = CareerApplicationRecord.create("Apple", "iOS Dev", CareerStatus.WISHLIST);
            assertThrows(InvalidStateTransitionException.class, () -> app.withStatus(CareerStatus.OFFER));
        }

        @Test
        @DisplayName("BC-F10-04: Transition to same state is a no-op")
        void testTransitionToSameState() {
            CareerApplicationRecord app = CareerApplicationRecord.create("Microsoft", "PM", CareerStatus.APPLIED);
            CareerApplicationRecord same = app.withStatus(CareerStatus.APPLIED);
            assertEquals(CareerStatus.APPLIED, same.status());
        }

        @Test
        @DisplayName("BC-F10-05: Non-null position and company validation")
        void testNullPositionThrows() {
            assertThrows(IllegalArgumentException.class, () ->
                    new CareerApplicationRecord(UUID.randomUUID(), DomainScope.CAREER, "ValidCo", null, CareerStatus.APPLIED, null, null, null, null, null));
        }
    }

    @Nested
    @DisplayName("Event Log & Rollback Boundary Tests")
    class EventLogRollbackBoundaries {

        @Test
        @DisplayName("BC-F11-01: Null payload before on CREATE and null payload after on DELETE")
        void testNullPayloadBoundaries() {
            UUID id = UUID.randomUUID();
            EventLogRecord create = eventLogService.recordMutation(DomainScope.CAREER, EntityType.CAREER_APPLICATION, id, ActionType.CREATE, null, "{\"name\":\"Job\"}");
            assertNull(create.payloadBefore());
            assertNotNull(create.payloadAfter());

            EventLogRecord delete = eventLogService.recordMutation(DomainScope.CAREER, EntityType.CAREER_APPLICATION, id, ActionType.DELETE, "{\"name\":\"Job\"}", null);
            assertNotNull(delete.payloadBefore());
            assertNull(delete.payloadAfter());
        }

        @Test
        @DisplayName("BC-F12-01: Chained rollback of multiple sequential mutations")
        void testChainedSequentialRollbacks() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();

            eventLogService.recordMutation(DomainScope.ACADEMICS, EntityType.TASK, id1, ActionType.CREATE, null, "T1");
            eventLogService.recordMutation(DomainScope.ACADEMICS, EntityType.TASK, id2, ActionType.CREATE, null, "T2");

            assertTrue(eventLogService.isEntityPresent(id1));
            assertTrue(eventLogService.isEntityPresent(id2));

            // Rollback 1: Undoes T2
            eventLogService.rollbackLastMutation(DomainScope.ACADEMICS);
            assertFalse(eventLogService.isEntityPresent(id2));
            assertTrue(eventLogService.isEntityPresent(id1));

            // Rollback 2: Undoes T1
            eventLogService.rollbackLastMutation(DomainScope.ACADEMICS);
            assertFalse(eventLogService.isEntityPresent(id1));

            // Rollback 3: No more mutations to undo
            assertThrows(NoMutationsToRollbackException.class, () ->
                    eventLogService.rollbackLastMutation(DomainScope.ACADEMICS));
        }

        @Test
        @DisplayName("BC-F12-02: Double rollback attempt on same event throws EventAlreadyRolledBackException")
        void testDoubleRollbackAttempt() {
            UUID id = UUID.randomUUID();
            EventLogRecord entry = eventLogService.recordMutation(DomainScope.PERSONAL, EntityType.HABIT, id, ActionType.CREATE, null, "H");

            eventLogService.rollbackEvent(entry.id());

            assertThrows(EventAlreadyRolledBackException.class, () ->
                    eventLogService.rollbackEvent(entry.id()));
        }

        @Test
        @DisplayName("BC-F12-03: Rollback on unknown UUID throws IllegalArgumentException")
        void testRollbackUnknownIdThrows() {
            assertThrows(IllegalArgumentException.class, () ->
                    eventLogService.rollbackEvent(UUID.randomUUID()));
        }

        @Test
        @DisplayName("BC-F12-04: Rollback strictly respects domain scoping and isolation")
        void testRollbackScopeIsolation() {
            UUID cId = UUID.randomUUID();
            eventLogService.recordMutation(DomainScope.CAREER, EntityType.CAREER_APPLICATION, cId, ActionType.CREATE, null, "C");

            // Attempt rollback in ACADEMICS should fail
            assertThrows(NoMutationsToRollbackException.class, () ->
                    eventLogService.rollbackLastMutation(DomainScope.ACADEMICS));
            assertTrue(eventLogService.isEntityPresent(cId));
        }
    }
}
