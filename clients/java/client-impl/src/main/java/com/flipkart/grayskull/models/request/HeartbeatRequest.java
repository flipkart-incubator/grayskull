package com.flipkart.grayskull.models.request;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * Body of {@code POST /v1/client/heartbeat} (schema version 1). Serialized only.
 * <p>
 * Each beat is a full snapshot with cumulative counters; the server upserts per
 * (workload, projectId, secretName). Workload identity travels in the
 * {@code Grayskull-Workload} header, not in the body.
 * </p>
 */
@Getter
@AllArgsConstructor
public final class HeartbeatRequest {
    public static final int SCHEMA_VERSION = 1;
    public static final String TRIGGER_PERIODIC = "PERIODIC";

    private final int schemaVersion;
    private final String trigger;
    private final Sdk sdk;
    /** Epoch millis when this beat was built. */
    private final long sentAt;
    private final List<HeldSecret> secrets;

    @Getter
    @AllArgsConstructor
    public static final class Sdk {
        private final String language;
        private final String version;
    }

    /** One ledger entry. Never carries secret values. */
    @Getter
    @AllArgsConstructor
    public static final class HeldSecret {
        private final String projectId;
        private final String secretName;
        /** Newest version held (fetched or hook-delivered). */
        private final int version;
        private final boolean refreshHook;
        private final long getCount;
        private final long errorCount;
        /** Epoch millis of the first / last getSecret call; 0 when never fetched. */
        private final long firstAccessAt;
        private final long lastAccessAt;
        private final int hookAppliedVersion;
        private final long hookFailureCount;
        /** Exception class and message of the last hook failure, truncated; null if none. */
        private final String lastHookError;
    }
}
