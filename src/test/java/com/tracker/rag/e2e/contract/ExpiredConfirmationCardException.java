package com.tracker.rag.e2e.contract;

public class ExpiredConfirmationCardException extends RuntimeException {
    public ExpiredConfirmationCardException(String message) {
        super(message);
    }
}
