package com.flipkart.grayskull.models.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * Request body for the {@code POST /v1/secrets/batch} endpoint.
 */
@Getter
@AllArgsConstructor(onConstructor = @__(@JsonCreator))
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BatchGetSecretsRequest {

    private final List<Entry> secrets;

    @Getter
    @AllArgsConstructor(onConstructor = @__(@JsonCreator))
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Entry {
        private final String projectId;
        private final String secretName;
        private final int lastKnownVersion;
    }
}
