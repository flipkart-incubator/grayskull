package com.flipkart.grayskull.models.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * A single entry in a {@link BatchGetSecretsRequest}, identifying a secret and
 * the last version the client has cached for it.
 * <p>
 * The server uses {@code lastKnownVersion} to decide whether it needs to return
 * updated data for that secret; secrets whose version on the server matches the
 * caller's {@code lastKnownVersion} are omitted from the response.
 * </p>
 * <p>
 * This class is immutable and thread-safe.
 * </p>
 */
@Getter
@AllArgsConstructor(onConstructor = @__(@JsonCreator))
@JsonIgnoreProperties(ignoreUnknown = true)
public final class SecretVersionEntry {

    /**
     * Project identifier that owns the secret.
     */
    private final String projectId;

    /**
     * Name of the secret within the project.
     */
    private final String secretName;

    /**
     * The last version number the caller has cached for this secret.
     */
    private final int lastKnownVersion;
}
