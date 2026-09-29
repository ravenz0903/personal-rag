package com.tracker.rag.e2e.contract;

public class InvalidDomainException extends RuntimeException {
    public InvalidDomainException(String message) {
        super(message);
    }
}
