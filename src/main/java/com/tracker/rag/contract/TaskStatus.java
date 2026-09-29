package com.tracker.rag.contract;

public enum TaskStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED;

    public boolean canTransitionTo(TaskStatus next) {
        if (this == next) return true;
        return switch (this) {
            case PENDING -> next == IN_PROGRESS || next == COMPLETED || next == CANCELLED;
            case IN_PROGRESS -> next == PENDING || next == COMPLETED || next == CANCELLED;
            case COMPLETED -> next == PENDING; // Reopening completed task
            case CANCELLED -> next == PENDING; // Cancelled tasks must go through PENDING before completing
        };
    }
}

