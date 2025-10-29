package com.flipkart.grayskull.spi.models;

import com.flipkart.grayskull.spi.models.enums.LifecycleState;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/**
 * Represents the actual sensitive data of a secret, including its version and metadata.
 * This entity is typically stored encrypted and versioned.
 * 
 * This is a plain POJO contract that the server module will implement with framework-specific annotations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class SecretData {

    /**
     * The unique identifier for this secret data version.
     * This is the primary key.
     */
    private String id;

    /**
     * The identifier of the Secret this data belongs to.
     */
    private String secretId;

    /**
     * The version of this specific secret data payload.
     * This is incremented each time the secret's value changes.
     */
    private long dataVersion;

    /**
     * The public part of the secret, which can be exposed without compromising security.
     * Optional, and its usage depends on the secret type.
     */
    private String publicPart;

    /**
     * The private/sensitive part of the secret.
     * This is the core sensitive information and should always be encrypted at rest.
     * Access to this field is typically restricted.
     */
    private String privatePart;

    /**
     * The identifier of the Key Management Service (KMS) key used for encrypting
     * and decrypting the privatePart.
     */
    private String kmsKeyId;

    /**
     * A reference or identifier for the secret in the underlying external provider
     * system (if applicable).
     * This is used when Grayskull is wrapping an existing secret management solution.
     */
    private String providerSecretRef;

    /**
     * A reference to the specific version of the secret in the underlying external
     * provider system (if applicable).
     */
    private String providerSecretVersionRef;

    /**
     * The timestamp when this specific version of the secret data was last accessed
     * or used.
     * Can be null if tracking is not enabled or the secret has not been used.
     */
    private Instant lastUsed;

    /**
     * The current lifecycle state of this secret data (e.g., "ACTIVE", "EXPIRED", "REVOKED").
     */
    private LifecycleState state;
}
