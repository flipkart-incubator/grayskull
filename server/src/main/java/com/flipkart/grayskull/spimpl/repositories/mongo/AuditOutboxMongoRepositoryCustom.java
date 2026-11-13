package com.flipkart.grayskull.spimpl.repositories.mongo;

import com.flipkart.grayskull.entities.AuditOutboxEntity;
import com.flipkart.grayskull.entities.AuditSyncStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Status operations on the audit outbox. Marking SYNCED is conditional on the entry still being PENDING,
 * so an entry an operator parked as FAILED is never flipped back.
 */
public interface AuditOutboxMongoRepositoryCustom {

    /** Oldest PENDING entries first, read from the primary so freshly committed entries are visible. */
    List<AuditOutboxEntity> findPending(int limit);

    long markSynced(Collection<String> ids, Instant syncedAt);

    long deleteSyncedBefore(Instant cutoff);

    Optional<Instant> oldestTimestamp(AuditSyncStatus status);

    long countByStatus(AuditSyncStatus status);
}
