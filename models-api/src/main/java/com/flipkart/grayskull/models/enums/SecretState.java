package com.flipkart.grayskull.models.enums;

/**
 * Secret lifecycle state.
 */
public enum SecretState {
    
    /**
     * Secret is active and available for use.
     */
    ACTIVE,

    /**
     * Secret is deleted (soft delete, retained for audit).
     */
    DELETED,

    /**
     * Secret is temporarily disabled.
     */
    DISABLED
} 