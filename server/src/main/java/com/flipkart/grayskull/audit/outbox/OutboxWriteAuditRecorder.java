package com.flipkart.grayskull.audit.outbox;

import com.flipkart.grayskull.mappers.AuditOutboxMapper;
import com.flipkart.grayskull.spi.models.AuditEntry;
import com.flipkart.grayskull.spimpl.repositories.mongo.AuditOutboxMongoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Records the audit of a write operation (create, upgrade, delete, destroy): saves it into the MongoDB outbox as
 * PENDING, inside the secret's transaction, so the audit exists if and only if the secret change commits.
 * The relayer job copies it to MySQL afterwards.
 */
@Component
@RequiredArgsConstructor
public class OutboxWriteAuditRecorder {

    private final AuditOutboxMongoRepository auditOutboxRepository;
    private final AuditOutboxMapper auditOutboxMapper;

    public void record(AuditEntry entry) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Write audits must be recorded inside the transaction of the audited operation");
        }
        auditOutboxRepository.insert(auditOutboxMapper.toPendingEntry(entry));
    }
}
