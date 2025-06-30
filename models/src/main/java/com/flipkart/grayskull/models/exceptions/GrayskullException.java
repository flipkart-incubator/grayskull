package com.flipkart.grayskull.models.exceptions;

import lombok.Getter;

/**
 * The base exception for all application-specific exceptions in Grayskull.
 * It encapsulates an {@link ErrorCode} for standardized error handling.
 */
@Getter
public class GrayskullException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * Constructs a new GrayskullException with the specified error code.
     * The detail message is the default message from the error code.
     *
     * @param errorCode The non-null {@link ErrorCode} associated with this exception.
     */
    public GrayskullException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * Constructs a new GrayskullException with the specified error code and detail message.
     *
     * @param errorCode The non-null {@link ErrorCode} associated with this exception.
     * @param message   The detail message, which can provide more context than the default.
     */
    public GrayskullException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * Constructs a new GrayskullException with the specified error code, detail message, and cause.
     *
     * @param errorCode The non-null {@link ErrorCode} associated with this exception.
     * @param message   The detail message.
     * @param cause     The cause of the exception.
     */
    public GrayskullException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
} 