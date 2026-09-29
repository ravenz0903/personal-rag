package com.tracker.rag.domain;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when an unrecognized or invalid domain token is supplied.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidDomainException extends RuntimeException {
    public InvalidDomainException(String message) {
        super(message);
    }

    public InvalidDomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
