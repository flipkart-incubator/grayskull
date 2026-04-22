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
 * each with the client's last known version.
 * </p>
 */
@Getter
@AllArgsConstructor(onConstructor = @__(@JsonCreator))
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BatchGetSecretsRequest {

    private final List<SecretVersionEntry> secrets;
}
