package com.flipkart.grayskull.models.exceptions;

import lombok.Getter;

@Getter
public enum ErrorCode {
    /**
     * The request is malformed or invalid.
     */
    INVALID_REQUEST(400, "invalid_request", "The request is invalid."),

    /**
     * The request requires authentication, but none was provided.
     */
    NOT_AUTHENTICATED(401, "not_authenticated", "The request is not authenticated."),

    /**
     * The requested secret could not be found.
     */
    SECRET_NOT_FOUND(404, "secret_not_found", "The requested secret could not be found."),

    /**
     * An attempt was made to create a secret with a name that already exists.
     */
    DUPLICATE_SECRET_NAME(409, "duplicate_secret_name", "A secret with the same name already exists.");

    /**
     * The HTTP status code associated with the error.
     */
    private final int statusCode;

    /**
     * A short, machine-readable error code string.
     */
    private final String errorCode;

    /**
     * A human-readable message describing the error.
     */
    private final String message;

    ErrorCode(int statusCode, String errorCode, String message) {
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.message = message;
    }


    public static ErrorCode fromErrorCode(String errorCode) {
        for (ErrorCode value : ErrorCode.values()) {
            if (value.errorCode.equals(errorCode)) {
                return value;
            }
        }
        throw new EnumConstantNotPresentException(ErrorCode.class, errorCode);
    }
}
