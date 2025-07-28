package com.flipkart.grayskull.models.dto.request;

import com.flipkart.grayskull.models.audit.AuditMask;

import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to upgrade secret data (create new version).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpgradeSecretDataRequest {
    
    /**
     * New public part (non-sensitive).
     */
    @NotBlank
    private String publicPart;
    
    /**
     * New private part (sensitive, masked in audit logs).
     */
    @AuditMask
    @NotBlank
    private String privatePart;
} 