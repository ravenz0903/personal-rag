package com.tracker.rag.e2e.contract;

public class CardAlreadyConfirmedException extends RuntimeException {
    public CardAlreadyConfirmedException(String message) {
        super(message);
    }
}
