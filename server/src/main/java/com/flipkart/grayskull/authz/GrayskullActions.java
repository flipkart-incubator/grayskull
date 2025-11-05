package com.flipkart.grayskull.authz;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

/**
 * Defines all authorization actions available in the Grayskull system.
 * Actions follow a hierarchical CRUD-based naming convention using dot notation
 * (e.g., "secrets.create", "secrets.read.value") for better organization and
 * maintainability.
 */
@Getter
@RequiredArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public enum GrayskullActions {

    /**
     * Permission to list the secrets within a project.
     */
    SECRETS_LIST("secrets.list"),

    /**
     * Permission to create a new secret in a project.
     */
    SECRETS_CREATE("secrets.create"),

    /**
     * Permission to read the metadata of a specific secret.
     */
    SECRETS_READ_METADATA("secrets.read.metadata"),

    /**
     * Permission to read the sensitive value of the latest version of a secret.
     */
    SECRETS_READ_VALUE("secrets.read.value"),

    /**
     * Permission to add a new version to an existing secret (update operation).
     */
    SECRETS_UPDATE("secrets.update"),

    /**
     * Permission to delete a secret and all its versions.
     */
    SECRETS_DELETE("secrets.delete"),

    /**
     * Permission to read the sensitive value of a specific version of a secret
     * (Admin operation).
     */
    SECRETS_READ_VERSION("secrets.read.version"),

    /**
     * Permission to read the audit trail for secrets.
     */
    AUDIT_READ("audit.read");

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
