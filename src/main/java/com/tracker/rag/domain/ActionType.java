package com.tracker.rag.domain;

/**
 * Mutation action types for the audit journal and state rollback.
 */
public enum ActionType {
    CREATE,
    UPDATE,
    DELETE,
    ROLLBACK;

    /**
     * Determines the inverse action type needed for rollback.
     */
    public ActionType inverse() {
        return switch (this) {
            case CREATE -> DELETE;
            case DELETE -> CREATE;
            case UPDATE -> UPDATE;
            case ROLLBACK -> throw new UnsupportedOperationException("Cannot invert a ROLLBACK event directly");
        };
    }
}
