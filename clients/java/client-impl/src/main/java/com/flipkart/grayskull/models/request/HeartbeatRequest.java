package com.flipkart.grayskull.models.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/** Body of {@code POST /v1/client/heartbeat}. */
@Getter
@AllArgsConstructor
public final class HeartbeatRequest {
    private final List<HeldSecret> secrets;

    /** One usage-ledger entry. */
    @Getter
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class HeldSecret {
        private final String projectId;
        private final String secretName;
        private final int version;
        /** Newest version a refresh hook ran successfully for; absent when no hook is registered. */
        private final Integer refreshHookAppliedVersion;
    }
}
