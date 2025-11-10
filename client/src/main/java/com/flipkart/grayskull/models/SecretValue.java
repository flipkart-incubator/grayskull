package com.flipkart.grayskull.models;

import lombok.Value;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Represents a secret value retrieved from Grayskull.
 * <p>
 * Contains the actual secret data including both public and private parts,
 * along with version information.
 * </p>
 */
@Value
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class SecretValue {

    /**
     * Data version number.
     */
    int dataVersion;

    /**
     * Public part of the secret.
     */
    String publicPart;

    /**
     * Private/sensitive part of the secret.
     */
    String privatePart;
}
