package com.flipkart.grayskull.models.dto.request;

/**
 * Secret data payload containing public and private parts.
 * This class extends SecretDataBase to inherit the common structure.
 */
public class SecretDataPayload extends SecretDataBase {
    
    /**
     * Default constructor.
     */
    public SecretDataPayload() {
        super();
    }
    
    /**
     * Constructor with public and private parts.
     * 
     * @param publicPart  The public part (non-sensitive).
     * @param privatePart The private part (sensitive, masked in audit logs).
     */
    public SecretDataPayload(String publicPart, String privatePart) {
        super(publicPart, privatePart);
    }
}
