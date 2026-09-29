package com.tracker.rag.service;

import com.tracker.rag.domain.ActionType;
import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.domain.EventAlreadyRolledBackException;
import com.tracker.rag.domain.EventNotFoundException;
import com.tracker.rag.domain.NoMutationsToRollbackException;
import com.tracker.rag.domain.UnscopedQueryException;
import com.tracker.rag.model.EventLogEntry;
import com.tracker.rag.repository.EventLogRepository;
import com.tracker.rag.service.rollback.RollbackDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class RollbackService {

    private static final Logger log = LoggerFactory.getLogger(RollbackService.class);

    private final EventLogRepository eventLogRepository;
    private final RollbackDispatcher rollbackDispatcher;

    public RollbackService(
        EventLogRepository eventLogRepository,
        RollbackDispatcher rollbackDispatcher
    ) {
        this.eventLogRepository = eventLogRepository;
        this.rollbackDispatcher = rollbackDispatcher;
    }

    /**
     * Undoes the most recent active mutation in the specified domain.
     * Enforces domain isolation: an undo in ACADEMICS will never undo a PERSONAL mutation.
     */
    @Transactional
    public EventLogEntry rollbackLastMutation(DomainScope domain) {
        if (domain == null) {
            throw new UnscopedQueryException("Domain scope must be explicitly specified (fail-closed)");
        }

        EventLogEntry latestEvent = eventLogRepository.findFirstByDomainAndUndoneFalseOrderByTimestampDesc(domain)
            .orElseThrow(() -> new NoMutationsToRollbackException(domain));

        return executeRollback(latestEvent);
    }

    /**
     * Undoes a specific event by its UUID.
     */
    @Transactional
    public EventLogEntry rollbackEvent(UUID eventId) {
        Objects.requireNonNull(eventId, "Event ID cannot be null");

        EventLogEntry targetEvent = eventLogRepository.findByIdForUpdate(eventId)
            .orElseThrow(() -> new EventNotFoundException(eventId));

        return executeRollback(targetEvent);
    }

    /**
     * Internal atomic rollback executor.
     */
    private EventLogEntry executeRollback(EventLogEntry targetEvent) {
        if (targetEvent.isUndone()) {
            throw new EventAlreadyRolledBackException(targetEvent.getId(), targetEvent.getUndoneByEventId());
        }

        if (targetEvent.getAction() == ActionType.ROLLBACK) {
            throw new UnsupportedOperationException("Cannot roll back an existing ROLLBACK event: " + targetEvent.getId());
        }

        log.info("Executing rollback on event {} (action={}, entityType={}, entityId={})",
            targetEvent.getId(), targetEvent.getAction(), targetEvent.getEntityType(), targetEvent.getEntityId());

        UUID rollbackEventId = UUID.randomUUID();
        String rollbackPayloadBefore;
        String rollbackPayloadAfter;

        // Apply inverse operation according to the original action
        switch (targetEvent.getAction()) {
            case CREATE -> {
                // Inverse of CREATE is DELETE
                rollbackDispatcher.executeDelete(targetEvent.getEntityType(), targetEvent.getEntityId());
                rollbackPayloadBefore = targetEvent.getPayloadAfter();
                rollbackPayloadAfter = null;
            }
            case UPDATE -> {
                // Inverse of UPDATE is RESTORE payloadBefore
                rollbackDispatcher.executeRestore(
                    targetEvent.getEntityType(),
                    targetEvent.getEntityId(),
                    targetEvent.getPayloadBefore()
                );
                rollbackPayloadBefore = targetEvent.getPayloadAfter();
                rollbackPayloadAfter = targetEvent.getPayloadBefore();
            }
            case DELETE -> {
                // Inverse of DELETE is RE-INSERT payloadBefore
                rollbackDispatcher.executeRestore(
                    targetEvent.getEntityType(),
                    targetEvent.getEntityId(),
                    targetEvent.getPayloadBefore()
                );
                rollbackPayloadBefore = null;
                rollbackPayloadAfter = targetEvent.getPayloadBefore();
            }
            default -> throw new IllegalStateException("Unexpected action: " + targetEvent.getAction());
        }

        // Mark target event as undone
        targetEvent.markUndoneBy(rollbackEventId);
        eventLogRepository.save(targetEvent);

        // Append new ROLLBACK event
        EventLogEntry rollbackEntry = new EventLogEntry(
            rollbackEventId,
            targetEvent.getDomain(),
            targetEvent.getEntityType(),
            targetEvent.getEntityId(),
            ActionType.ROLLBACK,
            EventLogService.getMonotonicInstant(),
            rollbackPayloadBefore,
            rollbackPayloadAfter
        );

        EventLogEntry savedRollback = eventLogRepository.save(rollbackEntry);
        log.info("Rollback complete. Created rollback event: {}", savedRollback.getId());
        return savedRollback;
    }
}
