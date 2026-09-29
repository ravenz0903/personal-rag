package com.tracker.rag.service.rollback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracker.rag.domain.EntityType;
import com.tracker.rag.domain.EntityRollbackException;
import com.tracker.rag.model.CareerApplication;
import com.tracker.rag.repository.CareerApplicationRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CareerApplicationRollbackHandler implements EntityRollbackHandler {

    private final CareerApplicationRepository repository;
    private final ObjectMapper objectMapper;

    public CareerApplicationRollbackHandler(CareerApplicationRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public EntityType getSupportedEntityType() {
        return EntityType.CAREER_APPLICATION;
    }

    @Override
    public void deleteEntity(UUID entityId) {
        repository.deleteById(entityId);
    }

    @Override
    public void restoreEntity(UUID entityId, String jsonPayload) {
        try {
            CareerApplication app = objectMapper.readValue(jsonPayload, CareerApplication.class);
            repository.save(app);
        } catch (Exception e) {
            throw new EntityRollbackException("Failed to deserialize and restore CareerApplication: " + entityId, e);
        }
    }
}
