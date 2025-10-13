package com.flipkart.grayskull.models.db;

import com.flipkart.grayskull.models.enums.LifecycleState;

import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Transient;
import org.springframework.data.annotation.Version;

/**
 * Represents a secret managed by the Grayskull system.
 * A secret is a logical entity that holds sensitive information, which can have multiple versions ({@link SecretData}).
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Secret {

    /**
     * The unique identifier for the secret. This is the primary key.
     */
    @Id
    private String id;

    /**
     * The identifier of the {@link Project} this secret is associated with in the resource hierarchy.
     */
    private String projectId;

    /**
     * The user-defined name of the secret (e.g., "database_password", "api_key").
     * This name should be unique within its associated {@code projectId}.
     */
    private String name;

    /**
     * System-managed labels for secret categorization and operational control.
     * Used for environment classification, access policies, and automated governance.
     * Examples: "environment: production", "data_sensitivity: high", "owner_team: security_platform".
     */
    private Map<String, String> systemLabels;

    /**
     * The version number of the currently active {@link SecretData} associated with this secret.
     */
    private Integer currentDataVersion;

    /**
     * Timestamp of when the secret's {@link SecretData} was last successfully rotated.
     * Can be null if the secret has never been rotated.
     */
    private Instant lastRotated;

    /**
     * The current lifecycle state of the secret (e.g., "ACTIVE", "INACTIVE", "PENDING_DELETION").
     */
    @Builder.Default
    private LifecycleState state = LifecycleState.ACTIVE;

    /**
     * The name of the {@link SecretProviderConfig} responsible for managing this secret (e.g., "DBCREDS").
     * If null or empty, Grayskull manages the secret directly.
     */
    private String provider;

    /**
     * Metadata specific to the provider used for this secret.
     * The structure of this map depends on the {@code provider}.
     * Examples: {"environment": "prod", "team": "backend", "db_instance": "mysql-01", "description": "API keys for payment service", "rotation_days": 30}
     */
    private Map<String, Object> providerMeta;

    /**
     * The version of this secret metadata entity itself.
     * This is incremented when fields like {@code name}, {@code systemLabels} are modified,
     * distinguishing it from {@code currentDataVersion} which tracks changes to the secret's value.
     */
    private Integer metadataVersion;

    /**
     * Optimistic locking version field managed by Spring Data.
     * Automatically incremented on each update to prevent concurrent modification conflicts.
     * This ensures that concurrent updates to the secret (e.g., adding versionssimultaneously) are detected and handled appropriately via {@link org.springframework.dao.OptimisticLockingFailureException}.
     */
    @Version
    private Long version;

    /**
     * The timestamp when this secret was created.
     */
    @CreatedDate
    private Instant creationTime;

    /**
     * The timestamp when this secret was last updated.
     */
    @LastModifiedDate
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
     * The latest (current) {@link SecretData} associated with this secret.
     * This field might not always be populated depending on the query to avoid fetching sensitive data unnecessarily.
     */
    @Transient
    private SecretData data;
} 