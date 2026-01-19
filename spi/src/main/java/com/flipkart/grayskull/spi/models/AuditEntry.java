package com.flipkart.grayskull.spi.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.Map;

/**
 * Represents a generic audit log entry in the Grayskull system.
 * Can be used to audit operations on any type of resource (secrets, projects, etc.).
 * Only successful operations are audited.
 * This is a plain POJO contract that the server module will implement with framework-specific annotations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class AuditEntry {

    /**
     * The unique identifier for this audit entry. This is the primary key.
     */
    private String id;

    /**
     * The identifier of the Project related to this audit event, if applicable.
     * This helps in correlating audit events to specific parts of the resource hierarchy.
     */
    private String projectId;

    /**
     * The type of resource being audited (e.g., "SECRET", "PROJECT", "SECRET_DATA").
     * This allows the audit system to handle different types of entities generically.
     */
    private String resourceType;

    /**
     * The name or identifier of the specific resource being audited.
     * For secrets, this would be the secret name. For projects, the project ID.
     */
    private String resourceName;

    /**
     * The version of the resource involved in the action, if applicable.
     * For example, if a secret value was read, this would indicate which version was accessed.
     * This field is optional and may be null for resources that don't have versions.
     */
    private Integer resourceVersion;

    /**
     * The type of action performed (e.g., "CREATE", "READ", "UPDATE", "DELETE").
     */
    private String action;

    /**
     * The identifier of the user or system principal that performed the action.
     * For system-initiated actions, this might be a service account or "SYSTEM".
     */
    private String userId;

    /**
     * ID of the user who delegated the request if this was a delegated request.
     */
    private String actorId;

    /**
     * The ip address and forwarded headers of the client that performed the action.
     */
    private Map<String, String> ips;

    /**
     * The timestamp when the action occurred, recorded in UTC with offset
     * information.
     */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * Additional metadata related to the audit event, stored as key-value pairs.
     * This can contain resource-specific information and operation details.
     */
    private Map<String, String> metadata;
}
