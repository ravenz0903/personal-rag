package com.tracker.rag.service.ai;

public class AiServiceException extends RuntimeException {
    private final int status;
    public AiServiceException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
}
