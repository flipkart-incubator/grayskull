package com.flipkart.grayskull.models.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * Request body for the {@code POST /v1/heartbeat} endpoint: the secrets this instance holds.
 */
@Getter
@AllArgsConstructor(onConstructor = @__(@JsonCreator))
@JsonIgnoreProperties(ignoreUnknown = true)
public final class HeartbeatRequest {

    private final List<HeldSecret> secrets;

    @Getter
    @AllArgsConstructor(onConstructor = @__(@JsonCreator))
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class HeldSecret {
        private final String projectId;
        private final String secretName;
        /** Newest version this instance holds. */
        private final int version;
        /** Whether a refresh hook is registered for this secret. */
        private final boolean refreshHook;
    }
}
