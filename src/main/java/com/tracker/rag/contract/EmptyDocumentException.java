package com.tracker.rag.contract;

public class EmptyDocumentException extends RuntimeException {
    public EmptyDocumentException(String message) {
        super(message);
    }
}

