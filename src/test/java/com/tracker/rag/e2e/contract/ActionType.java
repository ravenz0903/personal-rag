package com.tracker.rag.e2e.contract;

public enum ActionType {
    CREATE,
    UPDATE,
    DELETE,
    ROLLBACK;

    public ActionType inverse() {
        return switch (this) {
            case CREATE -> DELETE;
            case DELETE -> CREATE;
            case UPDATE -> UPDATE;
            case ROLLBACK -> throw new UnsupportedOperationException("Cannot directly invert a ROLLBACK action");
        };
    }
}
