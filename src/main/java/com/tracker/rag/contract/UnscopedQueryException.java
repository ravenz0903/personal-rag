package com.tracker.rag.contract;

public class UnscopedQueryException extends RuntimeException {
    public UnscopedQueryException(String message) {
        super(message);
    }
}

