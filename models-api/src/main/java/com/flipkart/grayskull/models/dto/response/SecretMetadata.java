package com.flipkart.grayskull.models.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import java.time.Instant;
import java.util.Map;

/**
 * Secret metadata without sensitive data.
 * Used in listing operations and metadata-only responses.
 */
@Data
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SecretMetadata {
    
    /**
     * Project identifier that owns this secret.
     */
    String projectId;
    
    /**
     * Secret name, unique within the project.
     */
    String name;
    
    /**
     * System labels for categorization and filtering.
     */
    Map<String, String> systemLabels;
    
    /**
     * Current active data version number.
     */
    int currentDataVersion;
    
    /**
     * Last rotation timestamp, null if never rotated.
     */
    Instant lastRotated;
    
    /**
     * Secret creation timestamp.
     */
    Instant creationTime;
    
    /**
     * Last metadata update timestamp.
     */
    Instant updatedTime;
    
    /**
     * User who created this secret.
     */
    String createdBy;
    
    /**
     * User who last updated this secret.
     */
    String updatedBy;
    
    /**
     * Current lifecycle state (ACTIVE, DELETED, DISABLED).
     */
    String state;
    
    /**
     * Provider managing this secret (SELF, DBCREDS).
     */
    String provider;
    
    /**
     * Provider-specific metadata.
     * Examples: {"environment": "prod", "team": "backend", "db_instance": "mysql-01", "description": "API keys for payment service"}
     */
    Map<String, Object> providerMeta;
    
    /**
     * Metadata entity version.
     */
    int metadataVersion;
} 