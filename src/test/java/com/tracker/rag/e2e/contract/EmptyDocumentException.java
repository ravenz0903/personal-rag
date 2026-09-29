package com.tracker.rag.e2e.contract;

public class EmptyDocumentException extends RuntimeException {
    public EmptyDocumentException(String message) {
        super(message);
    }
}
