package com.flipkart.grayskull.models.request;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/** Body of {@code POST /v1/client/heartbeat}. */
@Getter
@AllArgsConstructor
public final class HeartbeatRequest {
    public static final int SCHEMA_VERSION = 1;
    public static final String TRIGGER_PERIODIC = "PERIODIC";

    private final int schemaVersion;
    private final String trigger;
    private final Sdk sdk;
    /** Epoch millis. */
    private final long sentAt;
    private final List<HeldSecret> secrets;

    @Getter
    @AllArgsConstructor
    public static final class Sdk {
        private final String language;
        private final String version;
    }

    /** One usage-ledger entry. */
    @Getter
    @AllArgsConstructor
    public static final class HeldSecret {
        private final String projectId;
        private final String secretName;
        private final int version;
        private final boolean refreshHook;
        private final long getCount;
        private final long errorCount;
        private final long firstAccessAt;
        private final long lastAccessAt;
        private final int hookAppliedVersion;
        private final long hookFailureCount;
        private final String lastHookError;
    }
}
