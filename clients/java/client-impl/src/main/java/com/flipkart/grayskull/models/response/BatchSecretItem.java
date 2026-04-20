package com.flipkart.grayskull.models.response;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * A single item in a {@link BatchGetSecretsResponse}.
 * <p>
 * Carries the identity of the secret ({@code projectId}, {@code secretName})
 * along with its current decrypted data fields, flat (un-nested).
 * <p>
 * Only the fields needed by the client SDK are declared here; additional
 * server-side fields (e.g. timestamps, audit metadata) are ignored via
 * {@link JsonIgnoreProperties}.
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
}
