package com.tracker.rag.domain;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when an entity references an entity belonging to another domain scope,
 * violating domain boundary isolation (e.g. Personal task referencing an Academic course).
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class CrossDomainReferenceException extends RuntimeException {
    public CrossDomainReferenceException(String message) {
        super(message);
    }

    public CrossDomainReferenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
