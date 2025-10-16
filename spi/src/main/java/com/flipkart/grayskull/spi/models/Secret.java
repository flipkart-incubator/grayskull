package com.flipkart.grayskull.spi.models;

import com.flipkart.grayskull.spi.models.enums.LifecycleState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.Map;

/**
 * Represents a secret managed by the Grayskull system.
 * A secret is a logical entity that holds sensitive information, which can have
 * multiple versions (SecretData).
 * 
 * This is a plain POJO contract that the server module will implement with framework-specific annotations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class Secret {

    /**
     * The unique identifier for the secret. This is the primary key.
     */
    private String id;

    /**
     * The identifier of the Project this secret is associated with in the resource
     * hierarchy.
     */
    private String projectId;

    /**
     * The user-defined name of the secret (e.g., "database_password", "api_key").
     * This name should be unique within its associated projectId.
     */
    private String name;

    /**
     * System-managed labels for secret categorization and operational control.
     * Used for environment classification, access policies, and automated
     * governance.
     * Examples: "environment: production", "data_sensitivity: high", "owner_team: security_platform".
     */
    private Map<String, String> systemLabels;

    /**
     * The version number of the currently active SecretData associated with this secret.
     */
    private Integer currentDataVersion;

    /**
     * Timestamp of when the secret's SecretData was last successfully rotated.
     * Can be null if the secret has never been rotated.
     */
    private Instant lastRotated;

    /**
     * The current lifecycle state of the secret (e.g., "ACTIVE", "INACTIVE", "PENDING_DELETION").
     */
    @Builder.Default
    private LifecycleState state = LifecycleState.ACTIVE;

    /**
     * The name of the SecretProviderConfig responsible for managing this secret (e.g., "DBCREDS").
     * If null or empty, Grayskull manages the secret directly.
     */
    private String provider;

    /**
     * Metadata specific to the provider used for this secret.
     * The structure of this map depends on the provider.
     * Examples: {"environment": "prod", "team": "backend", "db_instance":
     * "mysql-01", "description": "API keys for payment service", "rotation_days": 30}
     */
    private Map<String, Object> providerMeta;

    /**
     * The version of this secret metadata entity itself.
     * This is incremented when fields like name, systemLabels are modified,
     * distinguishing it from currentDataVersion which tracks changes to the
     * secret's value.
     */
    private Integer metadataVersion;

    /**
     * Optimistic locking version field managed by persistence framework.
     * Automatically incremented on each update to prevent concurrent modification
     * conflicts.
     */
    private Long version;

    /**
     * The timestamp when this secret was created.
     */
    private Instant creationTime;

    /**
     * The timestamp when this secret was last updated.
     */
    private Instant updatedTime;

    /**
     * The identifier of the user or system principal that created this secret.
     */
    private String createdBy;

    /**
     * The identifier of the user or system principal that last updated this secret.
     */
    private String updatedBy;

    /**
     * The latest (current) SecretData associated with this secret.
     * This field might not always be populated depending on the query to avoid
     * fetching sensitive data unnecessarily.
     */
    private SecretData data;
}
