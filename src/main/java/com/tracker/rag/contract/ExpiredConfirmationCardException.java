package com.tracker.rag.contract;

public class ExpiredConfirmationCardException extends RuntimeException {
    public ExpiredConfirmationCardException(String message) {
        super(message);
    }
}

