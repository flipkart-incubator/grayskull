package com.flipkart.grayskull.models.enums;

/**
 * Represents the lifecycle state of an entity in the system.
 */
public enum LifecycleState {
    
    /**
     * Entity is active and available for use.
     */
    ACTIVE,

    /**
     * Entity is deleted (soft delete, retained for audit).
     */
    DELETED,

    /**
     * Entity is temporarily disabled.
     */
    DISABLED
}
