package com.tracker.rag.domain.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class UnscopedQueryException extends com.tracker.rag.domain.UnscopedQueryException {
    public UnscopedQueryException(String message) {
        super(message);
    }

    public UnscopedQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
