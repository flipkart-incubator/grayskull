package com.flipkart.grayskull.models.dto.request;



import com.flipkart.grayskull.audit.AuditMask;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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
     * Maximum length is 10,000 characters.
     */
    @NotBlank
    @Size(max = 10000, message = "Public part must not exceed 10,000 characters")
    private String publicPart;
    
    /**
     * Private part (sensitive, masked in audit logs).
     * Maximum length is 10,000 characters.
     */
    @AuditMask
    @NotBlank
    @Size(max = 10000, message = "Private part must not exceed 10,000 characters")
    private String privatePart;
}