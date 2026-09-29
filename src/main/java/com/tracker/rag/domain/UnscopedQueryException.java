package com.tracker.rag.domain;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a database query, service call, or API endpoint is executed without
 * an explicit, valid domain scope (fail-closed requirement).
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class UnscopedQueryException extends RuntimeException {
    public UnscopedQueryException(String message) {
        super(message);
    }

    public UnscopedQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
