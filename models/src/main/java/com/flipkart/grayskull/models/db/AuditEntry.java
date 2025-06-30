package com.flipkart.grayskull.models.db;

import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;

/**
 * Represents an audit log entry in the Grayskull system.
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
     * The name of the {@link Secret} related to this audit event, if applicable.
     * Combined with {@code projectId} and {@code secretVersion}, this precisely identifies the subject of the audit.
     */
    private String secretName;

    /**
     * The version of the {@link SecretData} involved in the action, if applicable.
     * For example, if a secret value was read, this would indicate which version was accessed.
     */
    private Integer secretVersion;

    /**
     * The type of action performed (e.g., "SECRET_CREATE", "SECRET_READ", "SECRET_ROTATE").
     */
    private String action;

    /**
     * The outcome status of the action (e.g., "SUCCESS", "FAILURE", "PENDING").
     */
    private String status;

    /**
     * The identifier of the user or system principal that performed the action.
     * For system-initiated actions, this might be a service account or "SYSTEM".
     */
    private String userId;

    /**
     * The timestamp when the action occurred, recorded in UTC with offset information.
     */
    private Instant timestamp;

    /**
     * Additional metadata related to the audit event, stored as key-value pairs.
     */
    private Map<String, String> metadata;

} 