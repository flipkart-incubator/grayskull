package com.flipkart.grayskull.app.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.flipkart.grayskull.spi.AsyncAuditLogger;
import com.flipkart.grayskull.spi.models.AuditEntry;
import com.flipkart.grayskull.spi.repositories.AuditEntryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.util.Map;

@Slf4j
public class DerbyAsyncAuditLogger implements AsyncAuditLogger {
    public static final String AUDIT_ERROR_METRIC = "audit-log-error";
    public static final String ACTION_TAG = "action";
    public static final String EXCEPTION_TAG = "exception";

    private final AuditProperties auditProperties;
    private final DerbyDao derbyDao;
    private final MeterRegistry meterRegistry;
    private final AuditEntryRepository auditEntryRepository;
    private final AuditCheckpointRepository auditCheckpointRepository;

    public DerbyAsyncAuditLogger(AuditProperties auditProperties, DerbyDao derbyDao, MeterRegistry meterRegistry, AuditEntryRepository auditEntryRepository, AuditCheckpointRepository auditCheckpointRepository) {
        this.auditProperties = auditProperties;
        this.derbyDao = derbyDao;
        this.meterRegistry = meterRegistry;
        this.auditEntryRepository = auditEntryRepository;
        this.auditCheckpointRepository = auditCheckpointRepository;
    }

    @Override
    public void log(AuditEntry auditEntry) {
        try {
            derbyDao.insertAuditEntry(auditEntry);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize audit entry", e);
            meterRegistry.counter(AUDIT_ERROR_METRIC, ACTION_TAG, "serialize", EXCEPTION_TAG, "JsonProcessingException").increment();
        } catch (SQLException e) {
            log.error("Failed to log audit entry", e);
            meterRegistry.counter(AUDIT_ERROR_METRIC, ACTION_TAG, "log", EXCEPTION_TAG, "SQLException").increment();
        }
    }

    @Transactional
    public int commitBatchToDb() throws SQLException, JsonProcessingException {
        AuditCheckpoint auditCheckpoint = auditCheckpointRepository.findByNodeName(auditProperties.getNodeName()).orElseGet(() -> new AuditCheckpoint(auditProperties.getNodeName()));
        long maxId = auditCheckpoint.getLogId();
        log.info("fetching {} audit entries from checkpoint {}", auditProperties.getBatchSize(), maxId);
        Map<Long, AuditEntry> auditEntries = derbyDao.fetchAuditEntries(maxId, auditProperties.getBatchSize());
        maxId = auditEntries.keySet().stream().mapToLong(Long::longValue).max().orElse(maxId);
        auditEntries.forEach((id, entry) ->
                entry.getMetadata().put("logId", auditProperties.getNodeName() + "." + id + "." + entry.getTimestamp())
        );
        log.info("found {} entries. storing them to db. current checkpoint is {}", auditEntries.size(), maxId);
        if (!auditEntries.isEmpty()) {
            auditEntryRepository.saveAll(auditEntries.values());
            auditCheckpoint.setLogId(maxId);
            auditCheckpointRepository.save(auditCheckpoint);
            derbyDao.deleteAuditEntries(maxId);
        }
        return auditEntries.size();
    }
}
