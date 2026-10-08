package com.tracker.rag.dto;

public class ExpiredConfirmationCardException extends RuntimeException {
    public ExpiredConfirmationCardException(String message) {
        super(message);
    }
}
