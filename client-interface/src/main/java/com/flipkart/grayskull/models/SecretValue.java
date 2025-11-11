package com.flipkart.grayskull.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

/**
 * Represents a secret value retrieved from Grayskull.
 * <p>
 * Contains the actual secret data including both public and private parts,
 * along with version information.
 * </p>
 */
@Getter
@Builder
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

    /**
     * Constructor for JSON deserialization.
     *
     * @param dataVersion Data version number
     * @param publicPart  Public part of the secret
     * @param privatePart Private/sensitive part of the secret
     */
    @JsonCreator
    public SecretValue(
            @JsonProperty("dataVersion") int dataVersion,
            @JsonProperty("publicPart") String publicPart,
            @JsonProperty("privatePart") String privatePart) {
        this.dataVersion = dataVersion;
        this.publicPart = publicPart;
        this.privatePart = privatePart;
    }
}
