package com.flipkart.grayskull.spi.models.enums;

/**
 * Represents the lifecycle state of an entity in the system.
 */
public enum LifecycleState {

    /**
     * Entity is active and available for use.
     */
    ACTIVE,

    /**
     * Entity is deleted.
     */
    DELETED,

    /**
     * Entity is temporarily disabled.
     */
    DISABLED
}
