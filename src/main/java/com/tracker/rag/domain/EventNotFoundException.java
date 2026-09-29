package com.tracker.rag.domain;

import java.util.UUID;

public class EventNotFoundException extends RuntimeException {
    public EventNotFoundException(UUID eventId) {
        super("Event log entry not found with ID: " + eventId);
    }

    public EventNotFoundException(String message) {
        super(message);
    }
}
