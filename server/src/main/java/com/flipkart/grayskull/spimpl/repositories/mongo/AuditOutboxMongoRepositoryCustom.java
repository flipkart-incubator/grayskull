package com.flipkart.grayskull.spimpl.repositories.mongo;

import com.flipkart.grayskull.entities.AuditOutboxEntity;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Status operations on the audit outbox. Marking SYNCED is conditional on the entry still being PENDING,
 * so an entry an operator parked as FAILED is never flipped back.
 */
public interface AuditOutboxMongoRepositoryCustom {

    /** Oldest PENDING entries first, read from the primary so freshly committed entries are visible. */
    List<AuditOutboxEntity> findPending(int limit);

    long markSynced(Collection<String> ids, Instant syncedAt);

    long deleteSyncedBefore(Instant cutoff);
}
