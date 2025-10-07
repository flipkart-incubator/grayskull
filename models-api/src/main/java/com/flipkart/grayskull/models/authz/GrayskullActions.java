package com.flipkart.grayskull.models.authz;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

/**
 * Defines all authorization actions available in the Grayskull system.
 * This is the central registry for permission-based access control operations.
 */
@Getter
@RequiredArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public enum GrayskullActions {

    /**
     * Permission to list the secrets within a project.
     */
    LIST_SECRETS("LIST_SECRETS"),

    /**
     * Permission to create a new secret in a project.
     */
    CREATE_SECRET("CREATE_SECRET"),

    /**
     * Permission to read the metadata of a specific secret.
     */
    READ_SECRET_METADATA("READ_SECRET_METADATA"),

    /**
     * Permission to read the sensitive value of the latest version of a secret.
     */
    READ_SECRET_VALUE("READ_SECRET_VALUE"),

    /**
     * Permission to add a new version to an existing secret.
     */
    ADD_SECRET_VERSION("ADD_SECRET_VERSION"),

    /**
     * Permission to delete a secret and all its versions.
     */
    DELETE_SECRET("DELETE_SECRET"),

    /**
     * Permission to read the sensitive value of a specific version of a secret (Admin).
     */
    READ_SECRET_VERSION_VALUE("READ_SECRET_VERSION_VALUE"),

    /**
     * Permission to read the audit trail for secrets.
     */
    READ_AUDIT("READ_AUDIT");

    @JsonValue
    @ToString.Include
    private final String value;

    /**
     * Finds an action enum by its string value for JSON deserialization.
     *
     * @param value the string value to match
     * @return the corresponding GrayskullActions enum
     * @throws IllegalArgumentException if no matching enum is found
     */
    @JsonCreator
    public static GrayskullActions fromValue(String value) {
        for (GrayskullActions action : values()) {
            if (action.value.equals(value)) {
                return action;
            }
        }
        throw new IllegalArgumentException("Unknown GrayskullActions value: " + value);
    }
} 