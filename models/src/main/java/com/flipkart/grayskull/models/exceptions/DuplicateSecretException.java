package com.flipkart.grayskull.models.exceptions;

/**
 * Thrown when attempting to create a secret with a name that already exists within the same project.
 */
public class DuplicateSecretException extends GrayskullException {

    private static final String ERROR_MESSAGE = "Secret with name '%s' already exists.";

    /**
     * Constructs a new DuplicateSecretException.
     *
     * @param name The name of the secret that already exists.
     */
    public DuplicateSecretException(String name) {
        super(ErrorCode.DUPLICATE_SECRET_NAME, String.format(ERROR_MESSAGE, name));
    }
} 