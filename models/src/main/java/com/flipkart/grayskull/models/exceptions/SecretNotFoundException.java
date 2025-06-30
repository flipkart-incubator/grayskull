package com.flipkart.grayskull.models.exceptions;

/**
 * Thrown when an operation is attempted on a secret that does not exist.
 */
public class SecretNotFoundException extends GrayskullException {

    private static final String ERROR_MESSAGE = "Secret with identifier '%s' not found.";

    /**
     * Constructs a new SecretNotFoundException.
     *
     * @param identifier The name or ID of the secret that could not be found.
     */
    public SecretNotFoundException(String identifier) {
        super(ErrorCode.SECRET_NOT_FOUND, String.format(ERROR_MESSAGE, identifier));
    }
} 