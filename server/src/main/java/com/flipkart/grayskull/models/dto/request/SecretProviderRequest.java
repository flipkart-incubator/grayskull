package com.flipkart.grayskull.models.dto.request;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.flipkart.grayskull.spi.models.BasicAuthAttributes;
import com.flipkart.grayskull.spi.models.NoneAuthAttributes;
import com.flipkart.grayskull.spi.models.OAuth2AuthAttributes;
import com.flipkart.grayskull.spi.models.enums.AuthMechanism;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "authMechanism", defaultImpl = NoneAuthAttributes.class)
    @JsonSubTypes(
            {
                    @JsonSubTypes.Type(value = BasicAuthAttributes.class, name = "BASIC"),
                    @JsonSubTypes.Type(value = OAuth2AuthAttributes.class, name = "OAUTH2"),
                    @JsonSubTypes.Type(value = NoneAuthAttributes.class, name = "NONE")
            }
    )
    @Valid
    private Object authAttributes;

    /**
     * Principal identifier for this provider.
     * Examples: service account name, user ID, etc.
     */
    @NotBlank
    private String principal;
}
