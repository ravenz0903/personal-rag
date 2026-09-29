package com.tracker.rag.e2e.stub;

import com.tracker.rag.e2e.contract.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * High-fidelity in-memory append-only event log and state rollback engine
 * per spec_report.md § 6 and PROJECT.md F11 & F12.
 */
public class InMemoryEventLogAndRollbackServiceStub implements EventLogAndRollbackService {

    private final List<EventLogRecord> eventJournal = new CopyOnWriteArrayList<>();
    private final Map<UUID, Object> liveEntities = new ConcurrentHashMap<>();

    @Override
    public EventLogRecord recordMutation(
            DomainScope domain,
            EntityType entityType,
            UUID entityId,
            ActionType action,
            String payloadBefore,
            String payloadAfter
    ) {
        DomainScope.validateScope(domain);
        Objects.requireNonNull(entityType, "entityType cannot be null");
        Objects.requireNonNull(entityId, "entityId cannot be null");
        Objects.requireNonNull(action, "action cannot be null");

        EventLogRecord entry = EventLogRecord.create(
                domain, entityType, entityId, action, payloadBefore, payloadAfter
        );
        eventJournal.add(entry);

        if (action == ActionType.CREATE || action == ActionType.UPDATE) {
            liveEntities.put(entityId, payloadAfter);
        } else if (action == ActionType.DELETE) {
            liveEntities.remove(entityId);
        }

        return entry;
    }

    @Override
    public List<EventLogRecord> getRecentEvents(DomainScope domain, int limit) {
        DomainScope.validateScope(domain);
        int max = Math.max(1, limit);
        List<EventLogRecord> domainEvents = new ArrayList<>();
        for (int i = eventJournal.size() - 1; i >= 0; i--) {
            EventLogRecord rec = eventJournal.get(i);
            if (rec.domain() == domain) {
                domainEvents.add(rec);
                if (domainEvents.size() >= max) break;
            }
        }
        return Collections.unmodifiableList(domainEvents);
    }

    @Override
    public synchronized EventLogRecord rollbackLastMutation(DomainScope domain) {
        DomainScope.validateScope(domain);

        EventLogRecord target = null;
        int targetIndex = -1;

        for (int i = eventJournal.size() - 1; i >= 0; i--) {
            EventLogRecord rec = eventJournal.get(i);
            if (rec.domain() == domain && !rec.undone() && rec.action() != ActionType.ROLLBACK) {
                target = rec;
                targetIndex = i;
                break;
            }
        }

        if (target == null) {
            throw new NoMutationsToRollbackException(domain);
        }

        return executeRollback(target, targetIndex);
    }

    @Override
    public synchronized EventLogRecord rollbackEvent(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId cannot be null");

        EventLogRecord target = null;
        int targetIndex = -1;

        for (int i = 0; i < eventJournal.size(); i++) {
            EventLogRecord rec = eventJournal.get(i);
            if (rec.id().equals(eventId)) {
                target = rec;
                targetIndex = i;
                break;
            }
        }

        if (target == null) {
            throw new IllegalArgumentException("Event not found with ID: " + eventId);
        }

        if (target.undone()) {
            throw new EventAlreadyRolledBackException(target.id(), target.undoneByEventId());
        }

        return executeRollback(target, targetIndex);
    }

    private EventLogRecord executeRollback(EventLogRecord target, int targetIndex) {
        UUID rollbackEventId = UUID.randomUUID();

        // 1. Mark target event as undone
        EventLogRecord updatedTarget = target.markUndone(rollbackEventId);
        eventJournal.set(targetIndex, updatedTarget);

        // 2. Invert state in simulated entity store
        if (target.action() == ActionType.CREATE) {
            liveEntities.remove(target.entityId());
        } else if (target.action() == ActionType.UPDATE) {
            liveEntities.put(target.entityId(), target.payloadBefore());
        } else if (target.action() == ActionType.DELETE) {
            liveEntities.put(target.entityId(), target.payloadBefore());
        }

        // 3. Create and append ROLLBACK event entry
        EventLogRecord rollbackRecord = new EventLogRecord(
                rollbackEventId,
                target.domain(),
                target.entityType(),
                target.entityId(),
                ActionType.ROLLBACK,
                Instant.now(),
                target.payloadAfter(),
                target.payloadBefore(),
                false,
                null
        );

        eventJournal.add(rollbackRecord);
        return rollbackRecord;
    }

    public boolean isEntityPresent(UUID entityId) {
        return liveEntities.containsKey(entityId);
    }

    public Object getEntityPayload(UUID entityId) {
        return liveEntities.get(entityId);
    }

    public int totalEventsCount() {
        return eventJournal.size();
    }
}
