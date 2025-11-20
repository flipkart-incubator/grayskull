package com.flipkart.grayskull.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Represents a secret value retrieved from Grayskull.
 * <p>
 * Contains the actual secret data including both public and private parts,
 * along with version information.
 * </p>
 */
@Getter
@AllArgsConstructor(onConstructor = @__(@JsonCreator))
@JsonIgnoreProperties(ignoreUnknown = true)
public final class SecretValue {

    /**
     * Data version number.
     */
    private final int dataVersion;

    /**
     * Public part of the secret.
     */
    private final String publicPart;

    /**
     * Private/sensitive part of the secret.
     */
    private final String privatePart;
}
