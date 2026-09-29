package com.tracker.rag.domain;

import java.util.UUID;

public class EventAlreadyRolledBackException extends RuntimeException {
    public EventAlreadyRolledBackException(UUID eventId, UUID undoneByEventId) {
        super("Event " + eventId + " has already been rolled back by event " + undoneByEventId);
    }

    public EventAlreadyRolledBackException(String message) {
        super(message);
    }
}
