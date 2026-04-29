package com.flipkart.grayskull.models.response;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * Response body for the {@code POST /v1/secrets/batch} endpoint.
 */
@Getter
@AllArgsConstructor(onConstructor = @__(@JsonCreator))
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BatchGetSecretsResponse {

    private final int updatedCount;
    private final List<UpdatedSecret> updatedSecrets;

    @Getter
    @AllArgsConstructor(onConstructor = @__(@JsonCreator))
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class UpdatedSecret {
        private final String projectId;
        private final String secretName;
        private final int dataVersion;
        private final String publicPart;
        private final String privatePart;
    }
}
