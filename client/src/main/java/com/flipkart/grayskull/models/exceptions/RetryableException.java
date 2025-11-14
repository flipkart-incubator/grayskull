package com.flipkart.grayskull.models.exceptions;

/**
 * Exception indicating that an operation failed but can be retried.
 * <p>
 * This exception is thrown for transient errors such as network timeouts,
 * connection failures, or temporary server unavailability (5xx errors).
 * </p>
 */
public final class RetryableException extends Exception {
    
    public RetryableException(String message) {
        super(message);
    }

    public RetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}

