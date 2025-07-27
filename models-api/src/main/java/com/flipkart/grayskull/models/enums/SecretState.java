package com.flipkart.grayskull.models.enums;

/**
 * Represents the lifecycle state of a secret.
 */
public enum SecretState {
    /**
     * The secret is active and can be used.
     */
    ACTIVE,

    /**
     * The secret has been deleted and is no longer accessible.
     */
    DELETED,

    /**
     * The secret is temporarily disabled and cannot be used.
     */
    DISABLED
} 