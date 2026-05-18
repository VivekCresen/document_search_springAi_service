package com.cresensolutions.document_search_springai_service.exception;

/**
 * Exception thrown when attempting to register a user with a username or email that already exists.
 */
public class UserAlreadyExistsException extends RuntimeException {
    public UserAlreadyExistsException(String message) {
        super(message);
    }

    public UserAlreadyExistsException(String message, Throwable cause) {
        super(message, cause);
    }
}
