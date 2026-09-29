package com.tracker.rag.service.rollback;

import com.tracker.rag.domain.EntityType;
import java.util.UUID;

/**
 * Strategy contract for executing inverse mutations per entity type.
 */
public interface EntityRollbackHandler {

    /**
     * The entity type supported by this handler.
     */
    EntityType getSupportedEntityType();

    /**
     * Inverse of CREATE: Delete the created entity.
     */
    void deleteEntity(UUID entityId);

    /**
     * Inverse of UPDATE or DELETE: Restore or re-insert the entity from its prior JSON state.
     */
    void restoreEntity(UUID entityId, String jsonPayload);
}
