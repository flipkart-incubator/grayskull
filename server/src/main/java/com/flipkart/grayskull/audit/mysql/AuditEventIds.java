package com.flipkart.grayskull.audit.mysql;

import com.flipkart.grayskull.spi.models.AuditEntry;
import lombok.experimental.UtilityClass;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Derives stable ids for audit rows, so the same event always maps to the same row and a retried copy is detected
 * as a duplicate.
 */
@UtilityClass
public class AuditEventIds {

    /** Metadata key the Derby flush adds to read audits ({@code node.rowId.timestamp}). */
    public static final String LOG_ID_KEY = "logId";

    /**
     * Event id of an audit entry: the Derby log id for read audits, otherwise the entry's own id (the outbox or
     * MongoDB id). Entries with neither get a random id; they are written once, so they need no deduplication.
     */
    public static String eventId(AuditEntry entry) {
        if (entry.getMetadata() != null && entry.getMetadata().get(LOG_ID_KEY) != null) {
            return entry.getMetadata().get(LOG_ID_KEY);
        }
        if (entry.getId() != null) {
            return entry.getId();
        }
        return UUID.randomUUID().toString();
    }

    /** Deterministic 16-byte row id for an event id. */
    public static UUID rowId(String eventId) {
        return UUID.nameUUIDFromBytes(eventId.getBytes(StandardCharsets.UTF_8));
    }

    /** MySQL {@code DATETIME(6)} keeps microseconds; truncating up front keeps cursors and stored values equal. */
    public static Instant toStoredTimestamp(Instant timestamp) {
        return (timestamp == null ? Instant.now() : timestamp).truncatedTo(ChronoUnit.MICROS);
    }
}
