package com.flipkart.grayskull.models.dto.request;

import com.flipkart.grayskull.models.audit.AuditMask;

import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Base class for secret data containing public and private parts.
 * This class can be extended by other request classes that need to handle secret data.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SecretDataBase {
    
    /**
     * Public part (non-sensitive).
     */
    @NotBlank
    private String publicPart;
    
    /**
     * Private part (sensitive, masked in audit logs).
     */
    @AuditMask
    @NotBlank
    private String privatePart;
}
