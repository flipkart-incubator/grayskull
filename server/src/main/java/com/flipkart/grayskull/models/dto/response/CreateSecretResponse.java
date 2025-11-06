package com.flipkart.grayskull.models.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import java.time.Instant;
import java.util.Map;

/**
 * Response after successfully creating a new secret.
 * Contains metadata without sensitive values.
 */
@Data
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateSecretResponse {
    
    /**
     * Project identifier.
     */
    String projectId;
    
    /**
     * Secret name.
     */
    String name;
    
    /**
     * System-managed labels assigned to the newly created secret.
     * Used for environment classification, access policies, and automated governance.
     * Examples: "environment: production", "data_sensitivity: high", "owner_team: security_platform".
     */
    Map<String, String> systemLabels;
    
    /**
     * Current data version (will be 1 for new secrets).
     */
    int currentDataVersion;
    
    /**
     * Last rotation timestamp (null for new secrets).
     */
    Instant lastRotated;
    
    /**
     * Secret state (ACTIVE for new secrets).
     */
    String state;
    
    /**
     * Provider managing this secret.
     */
    String provider;
    
    /**
     * Provider-specific metadata.
     * Examples: {"environment": "prod", "team": "backend", "db_instance": "mysql-01", "description": "API keys for payment service"}
     */
    Map<String, Object> providerMeta;
    
    /**
     * Metadata version (will be 1 for new secrets).
     */
    int metadataVersion;
} 