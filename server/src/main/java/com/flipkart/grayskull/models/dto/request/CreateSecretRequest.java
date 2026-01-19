package com.flipkart.grayskull.models.dto.request;

import com.flipkart.grayskull.validators.ValidSecretProvider;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to create a new secret.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateSecretRequest {
    
    /**
     * Secret name, unique within project (max 255 chars).
     */
    @NotBlank
    @Size(max = 255)
    private String name;

    /**
     * Provider managing this secret.
     */
    @NotBlank
    @ValidSecretProvider
    private String provider;

    /**
     * Provider-specific metadata.
     * Examples: {"environment": "prod", "team": "backend", "db_instance": "mysql-01", "description": "API keys for payment service", "rotation_days": 30}
     */
    private Map<String, Object> providerMeta = new HashMap<>();

    /**
     * Initial secret data (becomes version 1).
     */
    @NotNull
    @Valid
    private SecretDataPayload data;
}