package com.flipkart.grayskull.models.db;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;

import java.util.Map;

/**
 * Represents the configuration for a secret provider (e.g., DBCREDS).
 * This entity stores settings specific to how Grayskull interacts with an external secret management system.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SecretProviderConfig {

    /**
     * The unique identifier for the secret provider configuration (e.g., "DBCREDS").
     * This also serves as its ID in the database.
     */
    @Id
    private String id;

    /**
     * The client identifier used to authenticate with the secret provider.
     */
    private String clientId;

    /**
     * The organization or tenant this provider configuration belongs to (e.g., "FLIPKART", "FLIPKART/CT").
     * This helps in multi-tenancy scenarios.
     */
    private String org;

    /**
     * System-defined labels for this provider configuration.
     */
    private Map<String, String> systemLabels;

    /**
     * A map defining the parameters required by this provider, with their expected types (e.g., "STRING", "LIST").
     */
    private Map<String, String> requiredParams;

    /**
     * Indicates whether secret rotation for this provider is a manual process or can be automated.
     * If true, rotation requires manual intervention. If false, Grayskull might attempt automated rotation.
     */
    private boolean rotationManual;

} 