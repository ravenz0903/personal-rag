package com.tracker.rag.service.rollback;

import com.tracker.rag.domain.EntityType;
import com.tracker.rag.domain.EntityRollbackException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class EntityRollbackDispatcher implements RollbackDispatcher {

    private final Map<EntityType, EntityRollbackHandler> handlers = new EnumMap<>(EntityType.class);

    public EntityRollbackDispatcher(List<EntityRollbackHandler> handlerList) {
        if (handlerList != null) {
            for (EntityRollbackHandler handler : handlerList) {
                handlers.put(handler.getSupportedEntityType(), handler);
            }
        }
    }

    public void registerHandler(EntityRollbackHandler handler) {
        if (handler != null) {
            handlers.put(handler.getSupportedEntityType(), handler);
        }
    }

    public void executeDelete(EntityType entityType, UUID entityId) {
        EntityRollbackHandler handler = getHandler(entityType);
        try {
            handler.deleteEntity(entityId);
        } catch (Exception e) {
            throw new EntityRollbackException("Failed to delete entity " + entityId + " of type " + entityType + " during rollback", e);
        }
    }

    public void executeRestore(EntityType entityType, UUID entityId, String jsonPayload) {
        if (jsonPayload == null || jsonPayload.isBlank()) {
            throw new EntityRollbackException("Cannot restore entity " + entityId + " of type " + entityType + ": payload is empty");
        }
        EntityRollbackHandler handler = getHandler(entityType);
        try {
            handler.restoreEntity(entityId, jsonPayload);
        } catch (Exception e) {
            throw new EntityRollbackException("Failed to restore entity " + entityId + " of type " + entityType + " during rollback", e);
        }
    }

    private EntityRollbackHandler getHandler(EntityType entityType) {
        EntityRollbackHandler handler = handlers.get(entityType);
        if (handler == null) {
            throw new EntityRollbackException("No rollback handler registered for entity type: " + entityType);
        }
        return handler;
    }
}
