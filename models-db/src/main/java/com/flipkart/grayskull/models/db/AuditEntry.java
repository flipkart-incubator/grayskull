package com.flipkart.grayskull.models.db;

import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;

/**
 * Represents a generic audit log entry in the Grayskull system.
 * Can be used to audit operations on any type of resource (secrets, projects, etc.).
 * Only successful operations are audited.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditEntry {

    /**
     * The unique identifier for this audit entry. This is the primary key.
     */
    @Id
    private String id;

    /**
     * The identifier of the {@link Project} related to this audit event, if applicable.
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
     * The timestamp when the action occurred, recorded in UTC with offset information.
     */
    @CreatedDate
    private Instant timestamp;

    /**
     * Additional metadata related to the audit event, stored as key-value pairs.
     * This can contain resource-specific information and operation details.
     */
    private Map<String, String> metadata;

} 