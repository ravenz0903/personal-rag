package com.tracker.rag.contract;

public class CardAlreadyConfirmedException extends RuntimeException {
    public CardAlreadyConfirmedException(String message) {
        super(message);
    }
}

