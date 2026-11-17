package com.flipkart.grayskull;

import com.flipkart.grayskull.models.request.HeartbeatRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Per-secret usage for the heartbeat; entries are never removed. */
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

    /** Sets whether any refresh hook is still registered for the secret. */
    public void setHookRegistered(String projectId, String secretName, boolean registered) {
        Entry e = entry(projectId, secretName);
        synchronized (e) {
            e.hookRegistered = registered;
            if (!registered) {
                e.hookAppliedVersion = 0; // a hook registered later must apply a version itself
            }
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

    /** Entries that hold a version; {@code refreshHookAppliedVersion} is null until a hook has succeeded. */
    public List<HeartbeatRequest.HeldSecret> snapshot() {
        List<HeartbeatRequest.HeldSecret> out = new ArrayList<>(entries.size());
        for (Entry e : entries.values()) {
            synchronized (e) {
                if (e.version == 0) {
                    continue;
                }
                boolean applied = e.hookRegistered && e.hookAppliedVersion > 0;
                out.add(new HeartbeatRequest.HeldSecret(e.projectId, e.secretName, e.version,
                        applied ? e.hookAppliedVersion : null));
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
        private boolean hookRegistered;
        private int hookAppliedVersion;

        private Entry(String projectId, String secretName) {
            this.projectId = projectId;
            this.secretName = secretName;
        }
    }
}
