package com.tracker.rag.contract;

public class DomainExceptions {

    public static class UnscopedQueryException extends RuntimeException {
        public UnscopedQueryException(String message) {
            super(message);
        }
    }

    public static class CrossDomainReferenceException extends RuntimeException {
        public CrossDomainReferenceException(String message) {
            super(message);
        }
    }

    public static class InvalidDomainException extends RuntimeException {
        public InvalidDomainException(String message) {
            super(message);
        }
    }

    public static class InvalidStateTransitionException extends RuntimeException {
        public InvalidStateTransitionException(String message) {
            super(message);
        }
    }

    public static class InvalidSyllabusWeightException extends RuntimeException {
        public InvalidSyllabusWeightException(String message) {
            super(message);
        }
    }

    public static class FutureDateLoggingException extends RuntimeException {
        public FutureDateLoggingException(String message) {
            super(message);
        }
    }

    public static class EventAlreadyRolledBackException extends RuntimeException {
        public EventAlreadyRolledBackException(String message) {
            super(message);
        }
    }

    public static class NoMutationsToRollbackException extends RuntimeException {
        public NoMutationsToRollbackException(String message) {
            super(message);
        }
    }

    public static class ExpiredConfirmationCardException extends RuntimeException {
        public ExpiredConfirmationCardException(String message) {
            super(message);
        }
    }

    public static class CardAlreadyConfirmedException extends RuntimeException {
        public CardAlreadyConfirmedException(String message) {
            super(message);
        }
    }

    public static class EmptyDocumentException extends RuntimeException {
        public EmptyDocumentException(String message) {
            super(message);
        }
    }

    public static class DocumentParsingException extends RuntimeException {
        public DocumentParsingException(String message, Throwable cause) {
            super(message, cause);
        }
        public DocumentParsingException(String message) {
            super(message);
        }
    }
}

