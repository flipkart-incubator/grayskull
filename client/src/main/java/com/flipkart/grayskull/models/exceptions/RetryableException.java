package com.flipkart.grayskull.models.exceptions;

import lombok.Getter;

/**
 * Exception indicating that an operation failed but can be retried.
 * <p>
 * This exception is thrown for transient errors such as network timeouts,
 * connection failures, or temporary server unavailability (5xx errors).
 * </p>
 */
@Getter
public final class RetryableException extends Exception {
    
    private final int statusCode;
    
    public RetryableException(String message) {
        super(message);
        this.statusCode = 0;
    }

    public RetryableException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }
    
    public RetryableException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }
}

