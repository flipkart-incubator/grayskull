package com.flipkart.grayskull.models.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpgradeSecretDataRequest {
    /**
     * The new public part of the secret.
     */
    @NotBlank
    private String publicPart;
    /**
     * The new private, sensitive part of the secret.
     */
    @NotBlank
    private String privatePart;
} 