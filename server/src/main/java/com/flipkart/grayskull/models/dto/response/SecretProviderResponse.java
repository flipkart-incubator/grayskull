package com.flipkart.grayskull.models.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Response containing secret provider information.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SecretProviderResponse {
    
    /**
     * Provider name.
     */
    private String name;
    
    /**
     * Authentication mechanism for this provider.
     */
    private String authMechanism;
    
    /**
     * Authentication attributes specific to the auth mechanism.
     * Sensitive values should be masked or excluded.
     */
    private Map<String, String> authAttributes;
    
    /**
     * Principal identifier for this provider.
     */
    private String principal;
}
