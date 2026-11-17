package com.flipkart.grayskull;

import com.flipkart.grayskull.models.request.HeartbeatRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-secret usage reported by the heartbeat. One entry per secret, never removed;
 * holds no secret values.
 */
final class UsageLedger {

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    /** Records a successful {@code getSecret}. */
    public void recordFetch(String projectId, String secretName, int version) {
        recordVersion(projectId, secretName, version);
    }

    /** Records a version seen by the poller. */
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

    /** Records a successful hook run; failures are not recorded. */
    public void recordHookApplied(String projectId, String secretName, int version) {
        Entry e = entry(projectId, secretName);
        synchronized (e) {
            e.hookAppliedVersion = Math.max(e.hookAppliedVersion, version);
        }
    }

    /** Newest version held, or 0. */
    public int version(String projectId, String secretName) {
        Entry e = entries.get(key(projectId, secretName));
        if (e == null) {
            return 0;
        }
        synchronized (e) {
            return e.version;
        }
    }

    /** Copy of all entries; {@code refreshHookAppliedVersion} is null when no hook is registered. */
    public List<HeartbeatRequest.HeldSecret> snapshot() {
        List<HeartbeatRequest.HeldSecret> out = new ArrayList<>(entries.size());
        for (Entry e : entries.values()) {
            synchronized (e) {
                out.add(new HeartbeatRequest.HeldSecret(e.projectId, e.secretName, e.version,
                        e.hookCount > 0 ? e.hookAppliedVersion : null));
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

    /** Guarded by its own monitor. */
    private static final class Entry {
        private final String projectId;
        private final String secretName;
        private int version;
        private int hookCount;
        private int hookAppliedVersion;

        private Entry(String projectId, String secretName) {
            this.projectId = projectId;
            this.secretName = secretName;
        }
    }
}
