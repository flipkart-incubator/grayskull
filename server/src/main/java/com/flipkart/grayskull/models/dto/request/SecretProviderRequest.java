package com.flipkart.grayskull.models.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.flipkart.grayskull.spi.models.AuthAttributes;
import com.flipkart.grayskull.spi.models.enums.AuthMechanism;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Base request object to create and update a secret provider.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SecretProviderRequest {
    
    /**
     * Authentication mechanism for this provider.
     */
    @NotNull
    private AuthMechanism authMechanism;

    /**
     * Authentication attributes specific to the auth mechanism.
     * Examples: {"username": "admin", "password": "secret"} for BASIC
     *          {"audience": "xyz", "issuer_url": "https://..."} for OAUTH2
     */
    @NotNull
    private Map<String, String> authAttributes;

    /**
     * Principal identifier for this provider.
     * Examples: service account name, user ID, etc.
     */
    @NotBlank
    private String principal;

    /**
     * Field for storing authAttributes after they have been deserialized and validated
     */
    @JsonIgnore
    private AuthAttributes authAttributesProcessed;
}
