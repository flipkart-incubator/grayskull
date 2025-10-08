package com.flipkart.grayskull.models.enums;

/**
 * Defines the types of actions that can be audited within the system.
 */
public enum AuditAction {
    /**
     * Represents the creation of a new secret.
     */
    CREATE_SECRET,
    /**
     * Represent the action of reading private part of a secret
     */
    READ_SECRET,
    /**
     * Represents the update (upgrade) of an existing secret's data, creating a new version.
     */
    UPGRADE_SECRET_DATA,
    /**
     * Represents the deletion of a secret.
     */
    DELETE_SECRET
} 