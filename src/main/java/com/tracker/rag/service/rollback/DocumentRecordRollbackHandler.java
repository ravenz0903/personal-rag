package com.tracker.rag.service.rollback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.rag.domain.EntityType;
import com.tracker.rag.domain.EntityRollbackException;
import com.tracker.rag.model.DocumentRecord;
import com.tracker.rag.repository.DocumentRecordRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class DocumentRecordRollbackHandler implements EntityRollbackHandler {

    private final DocumentRecordRepository repository;
    private final ObjectMapper objectMapper;

    public DocumentRecordRollbackHandler(DocumentRecordRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public EntityType getSupportedEntityType() {
        return EntityType.DOCUMENT_RECORD;
    }

    @Override
    public void deleteEntity(UUID entityId) {
        repository.deleteById(entityId);
    }

    @Override
    public void restoreEntity(UUID entityId, String jsonPayload) {
        try {
            DocumentRecord doc = objectMapper.readValue(jsonPayload, DocumentRecord.class);
            repository.save(doc);
        } catch (Exception e) {
            throw new EntityRollbackException("Failed to deserialize and restore DocumentRecord: " + entityId, e);
        }
    }
}
