package com.flipkart.grayskull.models.response;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * Response body for the {@code POST /v1/secrets/batch} endpoint.
 * <p>
 * Contains only those secrets whose server-side version is ahead of the
 * caller's {@code lastKnownVersion}. The {@code updatedCount} is a convenience
 * mirror of {@code updatedSecrets.size()}.
 * </p>
 * <p>
 * This class is immutable and thread-safe.
 * </p>
 */
@Getter
@AllArgsConstructor(onConstructor = @__(@JsonCreator))
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BatchGetSecretsResponse {

    private final int updatedCount;
    private final List<BatchSecretItem> updatedSecrets;
}
