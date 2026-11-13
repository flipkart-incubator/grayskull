package com.flipkart.grayskull.entities;

/**
 * Sync state of a write audit waiting in the outbox.
 */
public enum AuditSyncStatus {
    /** Saved with the secret change, not yet in the audit store. */
    PENDING,
    /** Committed in the audit store. */
    SYNCED,
    /**
     * Set by an operator to park an entry that can never be stored (it would fail every relayer run and hold up the
     * entries behind it). The relayer skips it; set it back to PENDING to replay.
     */
    FAILED
}
