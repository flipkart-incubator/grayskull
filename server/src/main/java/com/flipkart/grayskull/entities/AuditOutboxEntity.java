package com.flipkart.grayskull.entities;

import com.flipkart.grayskull.spi.models.AuditEntry;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A write audit saved in the same MongoDB transaction as the secret change, waiting to be copied to the audit store.
 * Indexes are created by {@code AuditOutboxIndexes}.
 */
@SuperBuilder(toBuilder = true)
@Getter
@Setter
@NoArgsConstructor
@Document(collection = "auditOutbox")
public class AuditOutboxEntity extends AuditEntry {

    private AuditSyncStatus syncStatus;

    /** When the entry reached the audit store; drives the purge. */
    private Instant syncedAt;

    @Id
    @Override
    public String getId() {
        return super.getId();
    }
}
