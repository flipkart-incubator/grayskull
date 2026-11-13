package com.flipkart.grayskull.audit.mysql;

import com.flipkart.grayskull.audit.mysql.entity.AuditCheckpointRow;
import com.flipkart.grayskull.audit.mysql.repository.AuditCheckpointRowRepository;
import com.flipkart.grayskull.spi.AuditFlushStore;
import com.flipkart.grayskull.spi.models.AuditEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Derby flush target: the read-audit rows and the pod's checkpoint
 * ({@code audit_checkpoint}, name {@code derby:<node>}) commit in one MySQL transaction.
 */
@Component
@RequiredArgsConstructor
public class MysqlAuditFlushStore implements AuditFlushStore {

    static final String CHECKPOINT_PREFIX = "derby:";

    private final MysqlAuditStore store;
    private final AuditCheckpointRowRepository checkpointRepository;

    @Override
    public long lastFlushedId(String nodeName) {
        return checkpointRepository.findById(CHECKPOINT_PREFIX + nodeName)
                .map(AuditCheckpointRow::getLastLogId)
                .orElse(0L);
    }

    @Override
    @Transactional(transactionManager = MysqlAuditConfiguration.TRANSACTION_MANAGER)
    public void commitBatch(String nodeName, List<AuditEntry> entries, long maxId) {
        store.insertMissing(entries);
        AuditCheckpointRow checkpoint = checkpoint(nodeName);
        checkpoint.setLastLogId(maxId);
        checkpoint.setUpdatedAt(Instant.now());
        checkpointRepository.save(checkpoint);
    }

    @Override
    @Transactional(transactionManager = MysqlAuditConfiguration.TRANSACTION_MANAGER)
    public void markDrained(String nodeName, Instant flushStartedAt) {
        AuditCheckpointRow checkpoint = checkpoint(nodeName);
        checkpoint.setFlushedAt(flushStartedAt);
        checkpoint.setUpdatedAt(Instant.now());
        checkpointRepository.save(checkpoint);
    }

    @Override
    public Optional<Instant> lastCheckpointUpdate(String nodeName) {
        return checkpointRepository.findById(CHECKPOINT_PREFIX + nodeName).map(AuditCheckpointRow::getUpdatedAt);
    }

    @Override
    public void deleteCheckpoint(String nodeName) {
        checkpointRepository.deleteById(CHECKPOINT_PREFIX + nodeName);
    }

    private AuditCheckpointRow checkpoint(String nodeName) {
        AuditCheckpointRow checkpoint = checkpointRepository.findById(CHECKPOINT_PREFIX + nodeName)
                .orElseGet(() -> new AuditCheckpointRow(CHECKPOINT_PREFIX + nodeName));
        if (checkpoint.getLastLogId() == null) {
            checkpoint.setLastLogId(0L);
        }
        return checkpoint;
    }
}
