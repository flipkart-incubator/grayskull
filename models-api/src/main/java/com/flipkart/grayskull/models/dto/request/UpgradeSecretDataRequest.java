package com.flipkart.grayskull.models.dto.request;

import lombok.NoArgsConstructor;

/**
 * Request to upgrade secret data (create new version).
 * Extends SecretDataBase to inherit publicPart and privatePart fields.
 */
@NoArgsConstructor
public class UpgradeSecretDataRequest extends SecretDataBase {
    
    /**
     * Constructor with public and private parts.
     * 
     * @param publicPart  The new public part (non-sensitive).
     * @param privatePart The new private part (sensitive, masked in audit logs).
     */
    public UpgradeSecretDataRequest(String publicPart, String privatePart) {
        super(publicPart, privatePart);
    }
} 