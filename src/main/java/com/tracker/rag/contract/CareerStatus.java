package com.tracker.rag.contract;

public enum CareerStatus {
    WISHLIST,
    APPLIED,
    OA_RECEIVED,
    INTERVIEW_SCHEDULED,
    OFFER,
    REJECTED,
    WITHDRAWN;

    public boolean isTerminal() {
        return this == REJECTED || this == WITHDRAWN;
    }

    public boolean canTransitionTo(CareerStatus next) {
        if (this == next) return true;
        return switch (this) {
            case WISHLIST -> next == APPLIED || next == WITHDRAWN;
            case APPLIED -> next == OA_RECEIVED || next == INTERVIEW_SCHEDULED || next == REJECTED || next == WITHDRAWN;
            case OA_RECEIVED -> next == INTERVIEW_SCHEDULED || next == REJECTED || next == WITHDRAWN;
            case INTERVIEW_SCHEDULED -> next == OFFER || next == REJECTED || next == WITHDRAWN;
            case OFFER -> next == WITHDRAWN; // Can withdraw or accept (terminal)
            case REJECTED, WITHDRAWN -> false; // Terminal states: can only be altered via event rollback
        };
    }
}

