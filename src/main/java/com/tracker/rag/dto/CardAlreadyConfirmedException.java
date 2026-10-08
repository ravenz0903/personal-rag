package com.tracker.rag.dto;

public class CardAlreadyConfirmedException extends RuntimeException {
    public CardAlreadyConfirmedException(String message) {
        super(message);
    }
}
