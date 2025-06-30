package com.flipkart.grayskull.models.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpgradeSecretDataRequest {
    /**
     * The new public part of the secret, if any.
     */
    private String publicPart;
    /**
     * The new private, sensitive part of the secret.
     */
    private String privatePart;
} 