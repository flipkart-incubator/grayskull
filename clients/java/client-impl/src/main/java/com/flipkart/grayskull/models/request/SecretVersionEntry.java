package com.flipkart.grayskull.models.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * A single entry in a {@link BatchGetSecretsRequest}, identifying a secret and
 * the last version the client has cached for it.
 */
@Getter
@AllArgsConstructor(onConstructor = @__(@JsonCreator))
@JsonIgnoreProperties(ignoreUnknown = true)
public final class SecretVersionEntry {

    private final String projectId;
    private final String secretName;
    private final int lastKnownVersion;
}
