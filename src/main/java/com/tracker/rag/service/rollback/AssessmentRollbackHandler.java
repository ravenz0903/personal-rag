package com.tracker.rag.service.rollback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.rag.domain.EntityType;
import com.tracker.rag.domain.EntityRollbackException;
import com.tracker.rag.model.Assessment;
import com.tracker.rag.repository.AssessmentRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AssessmentRollbackHandler implements EntityRollbackHandler {

    private final AssessmentRepository repository;
    private final ObjectMapper objectMapper;

    public AssessmentRollbackHandler(AssessmentRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public EntityType getSupportedEntityType() {
        return EntityType.ASSESSMENT;
    }

    @Override
    public void deleteEntity(UUID entityId) {
        repository.deleteById(entityId);
    }

    @Override
    public void restoreEntity(UUID entityId, String jsonPayload) {
        try {
            Assessment assessment = objectMapper.readValue(jsonPayload, Assessment.class);
            repository.save(assessment);
        } catch (Exception e) {
            throw new EntityRollbackException("Failed to deserialize and restore Assessment: " + entityId, e);
        }
    }
}
