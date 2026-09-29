package com.tracker.rag.domain;

public class EntityRollbackException extends RuntimeException {
    public EntityRollbackException(String message, Throwable cause) {
        super(message, cause);
    }

    public EntityRollbackException(String message) {
        super(message);
    }
}
