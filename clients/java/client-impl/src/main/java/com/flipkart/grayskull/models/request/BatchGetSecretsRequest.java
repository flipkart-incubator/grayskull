package com.flipkart.grayskull.models.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * Request body for the {@code POST /v1/secrets/batch} endpoint.
 * <p>
 * Carries the set of secrets the client wants to check for version changes,
 * along with the caller's last known version for each. The server responds with
 * only the entries whose versions have advanced.
 * </p>
 * <p>
 * This class is immutable and thread-safe.
 * </p>
 */
@Getter
@AllArgsConstructor(onConstructor = @__(@JsonCreator))
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BatchGetSecretsRequest {

    /**
     * Secrets to check, each with the caller's last known version.
     * The server enforces a bound on batch size (currently 1-50).
     */
    private final List<SecretVersionEntry> secrets;
}
