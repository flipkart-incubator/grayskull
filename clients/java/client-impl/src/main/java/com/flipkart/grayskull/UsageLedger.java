package com.flipkart.grayskull;

import com.flipkart.grayskull.models.request.HeartbeatRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-secret usage record that feeds the heartbeat.
 * <p>
 * One entry per {@code projectId:secretName}, created on the first fetch or on hook
 * registration and never removed. It never stores secret values or hashes. Counters are
 * cumulative, so every heartbeat is a full snapshot that the server can upsert idempotently.
 * </p>
 * <p>
 * Writers call it directly: {@link GrayskullClientImpl#getSecret} (fetch outcome),
 * {@link HookRefreshPoller} (hook register/unregister, versions seen, hook outcome).
 * </p>
 */
final class UsageLedger {

    /** Max length of {@code lastHookError} (exception class plus message). */
    private static final int MAX_ERROR_LENGTH = 256;

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public UsageLedger() {
        this(System::currentTimeMillis);
    }

    private UsageLedger(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Records one {@code getSecret} call.
     *
     * @param version the fetched version, or {@code null} when the call failed
     */
    public void recordGet(String projectId, String secretName, Integer version) {
        long now = clock.getAsLong();
        Entry e = entry(projectId, secretName);
        synchronized (e) {
            if (version == null) {
                e.errorCount++;
            } else {
                e.getCount++;
                e.version = Math.max(e.version, version);
            }
            if (e.firstAccessAt == 0) {
                e.firstAccessAt = now;
            }
            e.lastAccessAt = now;
        }
    }

    /** A newer version seen by the hook poller (recorded before the hooks run). */
    public void recordVersion(String projectId, String secretName, int version) {
        Entry e = entry(projectId, secretName);
        synchronized (e) {
            e.version = Math.max(e.version, version);
        }
    }

    public void recordHookRegistered(String projectId, String secretName) {
        Entry e = entry(projectId, secretName);
        synchronized (e) {
            e.hookCount++;
        }
    }

    public void recordHookUnregistered(String projectId, String secretName) {
        Entry e = entry(projectId, secretName);
        synchronized (e) {
            e.hookCount = Math.max(0, e.hookCount - 1);
        }
    }

    public void recordHookApplied(String projectId, String secretName, int version) {
        Entry e = entry(projectId, secretName);
        synchronized (e) {
            e.hookAppliedVersion = Math.max(e.hookAppliedVersion, version);
        }
    }

    public void recordHookFailed(String projectId, String secretName, Throwable error) {
        Entry e = entry(projectId, secretName);
        synchronized (e) {
            e.hookFailureCount++;
            e.lastHookError = describe(error);
        }
    }

    /** Newest version held for {@code projectId:secretName}, or 0. Used to seed hook registration. */
    public int version(String projectId, String secretName) {
        Entry e = entries.get(key(projectId, secretName));
        if (e == null) {
            return 0;
        }
        synchronized (e) {
            return e.version;
        }
    }

    /** A consistent copy of every entry, in no particular order. */
    public List<HeartbeatRequest.HeldSecret> snapshot() {
        List<HeartbeatRequest.HeldSecret> out = new ArrayList<>(entries.size());
        for (Entry e : entries.values()) {
            synchronized (e) {
                out.add(new HeartbeatRequest.HeldSecret(
                        e.projectId, e.secretName, e.version, e.hookCount > 0,
                        e.getCount, e.errorCount, e.firstAccessAt, e.lastAccessAt,
                        e.hookAppliedVersion, e.hookFailureCount, e.lastHookError));
            }
        }
        return out;
    }

    private Entry entry(String projectId, String secretName) {
        return entries.computeIfAbsent(key(projectId, secretName), k -> new Entry(projectId, secretName));
    }

    private static String key(String projectId, String secretName) {
        return projectId + ":" + secretName;
    }

    /** Exception class plus message, truncated. Diagnostics only. */
    private static String describe(Throwable error) {
        if (error == null) {
            return null;
        }
        String message = error.getMessage();
        String text = message == null ? error.getClass().getName() : error.getClass().getName() + ": " + message;
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }

    /** Mutable state for one secret; guarded by its own monitor. */
    private static final class Entry {
        private final String projectId;
        private final String secretName;
        private long getCount;
        private long errorCount;
        private long firstAccessAt;
        private long lastAccessAt;
        private int version;
        private int hookCount;
        private int hookAppliedVersion;
        private long hookFailureCount;
        private String lastHookError;

        private Entry(String projectId, String secretName) {
            this.projectId = projectId;
            this.secretName = secretName;
        }
    }
}
