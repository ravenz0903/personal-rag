package com.tracker.rag.dto;

public class EmptyDocumentException extends RuntimeException {
    public EmptyDocumentException(String message) {
        super(message);
    }
}
