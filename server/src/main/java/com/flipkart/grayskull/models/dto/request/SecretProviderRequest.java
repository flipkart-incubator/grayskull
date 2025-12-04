package com.flipkart.grayskull.models.dto.request;

import com.flipkart.grayskull.spi.models.enums.AuthMechanism;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request to update an existing secret provider.
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
    @Size(max = 5)
    private Map<@Size(max = 20) String, @Size(max = 100) String> authAttributes;

    /**
     * Principal identifier for this provider.
     * Examples: service account name, user ID, etc.
     */
    @NotBlank
    private String principal;
}
