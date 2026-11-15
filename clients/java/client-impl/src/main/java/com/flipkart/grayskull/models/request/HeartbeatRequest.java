package com.flipkart.grayskull.models.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/** Body of {@code POST /v1/heartbeat}. */
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
        private final int version;
        private final boolean refreshHook;
    }
}
