package com.flipkart.grayskull.models.enums;

/**
 * Represents the provider of a secret.
 */
public enum SecretProvider {
    /**
     * The secret is provided by the user.
     */
    SELF,

    /**
     * The secret is provided by the database credentials management system.
     */
    DBCREDS
}
