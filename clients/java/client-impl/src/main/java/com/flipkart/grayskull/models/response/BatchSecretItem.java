package com.flipkart.grayskull.models.response;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.flipkart.grayskull.models.SecretValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * A single updated secret entry inside a {@link BatchGetSecretsResponse}.
 * <p>
 * Adds {@code projectId} and {@code secretName} to the usual secret payload
 * so callers can correlate each returned item with its registered hook. Any
 * server-side fields the client does not care about are ignored via
 * {@link JsonIgnoreProperties}.
 * </p>
 * <p>
 * This class is immutable and thread-safe.
 * </p>
 */
@Getter
@AllArgsConstructor(onConstructor = @__(@JsonCreator))
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BatchSecretItem {

    private final String projectId;
    private final String secretName;
    private final int dataVersion;
    private final String publicPart;
    private final String privatePart;

    /**
     * Returns the {@code "projectId:secretName"} reference this item corresponds to.
     */
    public String getSecretRef() {
        return projectId + ":" + secretName;
    }

    /**
     * Projects this wire DTO into the public {@link SecretValue} type that is
     * handed to consumer refresh hooks.
     */
    public SecretValue toSecretValue() {
        return new SecretValue(dataVersion, publicPart, privatePart);
    }
}
