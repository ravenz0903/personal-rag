package com.tracker.rag.domain;

public class NoMutationsToRollbackException extends RuntimeException {
    public NoMutationsToRollbackException(DomainScope domain) {
        super("No active mutations found in domain " + domain + " to roll back.");
    }

    public NoMutationsToRollbackException(String message) {
        super(message);
    }
}
