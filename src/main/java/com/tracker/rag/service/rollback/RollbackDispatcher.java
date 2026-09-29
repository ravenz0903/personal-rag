package com.tracker.rag.service.rollback;

import com.tracker.rag.domain.EntityType;
import java.util.UUID;

/**
 * Dispatcher interface for executing inverse entity mutations during state rollback.
 */
public interface RollbackDispatcher {

    /**
     * Executes the inverse deletion of an entity.
     */
    void executeDelete(EntityType entityType, UUID entityId);

    /**
     * Executes the restoration or re-insertion of an entity from its JSON payload.
     */
    void executeRestore(EntityType entityType, UUID entityId, String jsonPayload);
}
