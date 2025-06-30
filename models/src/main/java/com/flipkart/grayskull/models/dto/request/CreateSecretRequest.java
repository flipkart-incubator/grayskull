package com.flipkart.grayskull.models.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Map;

import com.flipkart.grayskull.models.enums.SecretProvider;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateSecretRequest {
    /**
     * The user-defined name of the secret. Must be unique within a project.
     */
    @NotBlank
    @Size(max = 255)
    private String name;

    /**
     * The name of the provider responsible for managing this secret.
     */
    @NotBlank
    private SecretProvider provider;

    /**
     * Metadata specific to the provider.
     */
    private Map<String, Object> providerMeta;

    /**
     * The actual secret data payload.
     */
    @NotNull
    @Valid
    private SecretDataPayload data;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SecretDataPayload {
        /**
         * The public part of the secret, if any.
         */
        String publicPart;
        /**
         * The private, sensitive part of the secret.
         */
        String privatePart;
    }
} 