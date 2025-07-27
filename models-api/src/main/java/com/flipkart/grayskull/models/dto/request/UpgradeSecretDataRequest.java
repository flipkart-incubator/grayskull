package com.flipkart.grayskull.models.dto.request;

import com.flipkart.grayskull.models.audit.AuditMask;
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
     * This field is marked for masking in audit logs.
     */
    @AuditMask
    @NotBlank
    private String privatePart;
} 