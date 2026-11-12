package com.flipkart.grayskull.app.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.spi.AsyncAuditLogger;
import com.flipkart.grayskull.spi.AuditFlushStore;
import com.flipkart.grayskull.spi.models.AuditEntry;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class DerbyAsyncAuditLogger implements AsyncAuditLogger {
    public static final String AUDIT_ERROR_METRIC = "audit-log-error";
    public static final String ACTION_TAG = "action";
    public static final String EXCEPTION_TAG = "exception";
    private static final String TABLE_ALREADY_EXISTS = "X0Y32";

    private final AuditProperties auditProperties;
    private final Connection connection;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final AuditFlushStore auditFlushStore;

    public DerbyAsyncAuditLogger(AuditProperties auditProperties, ObjectMapper objectMapper, MeterRegistry meterRegistry, AuditFlushStore auditFlushStore) throws SQLException {
        this.connection = DriverManager.getConnection(auditProperties.getDerbyUrl());
        this.auditProperties = auditProperties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.auditFlushStore = auditFlushStore;
    }

    @PostConstruct
    public void init() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE audits (id BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY(START WITH 1, INCREMENT BY 1), event LONG VARCHAR)");
        } catch (SQLException e) {
            if (!TABLE_ALREADY_EXISTS.equals(e.getSQLState())) {
                throw e;
            }
            return;
        }
        // A new buffer numbers its rows from 1 again. A checkpoint left by a previous buffer of this node (e.g. the
        // pod's disk was replaced but the pod name kept) would make the flush skip every row up to its id.
        log.info("created a new audit buffer; resetting the checkpoint of node {}", auditProperties.getNodeName());
        auditFlushStore.deleteCheckpoint(auditProperties.getNodeName());
    }

    @PreDestroy
    public void cleanup() throws SQLException {
        connection.close();
    }

    @Override
    public void log(AuditEntry auditEntry) {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO audits (event) VALUES (?)")) {
            String eventString = objectMapper.writeValueAsString(auditEntry);
            statement.setString(1, eventString);
            statement.execute();
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize audit entry", e);
            meterRegistry.counter(AUDIT_ERROR_METRIC, ACTION_TAG, "serialize", EXCEPTION_TAG, "JsonProcessingException").increment();
        } catch (SQLException e) {
            log.error("Failed to log audit entry", e);
            meterRegistry.counter(AUDIT_ERROR_METRIC, ACTION_TAG, "log", EXCEPTION_TAG, "SQLException").increment();
        }
    }

    /**
     * Flushes one batch of buffered entries to the {@link AuditFlushStore}. The store saves the entries and the
     * node checkpoint atomically; the rows are deleted from Derby only after that succeeds, so a crash in between
     * only re-sends rows, which the store skips as duplicates.
     *
     * @return number of entries flushed; 0 when the buffer is empty
     */
    public int commitBatchToDb() throws SQLException, JsonProcessingException {
        String nodeName = auditProperties.getNodeName();
        long maxId = auditFlushStore.lastFlushedId(nodeName);
        log.info("fetching {} audit entries from checkpoint {}", auditProperties.getBatchSize(), maxId);
        List<AuditEntry> auditEntries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, event FROM audits WHERE id > ? ORDER BY id FETCH FIRST ? ROWS ONLY")) {
            statement.setLong(1, maxId);
            statement.setInt(2, auditProperties.getBatchSize());
            statement.execute();
            ResultSet resultSet = statement.getResultSet();
            while (resultSet.next()) {
                maxId = resultSet.getLong(1);
                String eventSting = resultSet.getString(2);
                AuditEntry auditEntry = objectMapper.readValue(eventSting, AuditEntry.class);
                auditEntry.getMetadata().put("logId", nodeName + "." + maxId + "." + auditEntry.getTimestamp());
                auditEntries.add(auditEntry);
            }
        }
        log.info("found {} entries. storing them to db. current checkpoint is {}", auditEntries.size(), maxId);
        if (!auditEntries.isEmpty()) {
            auditFlushStore.commitBatch(nodeName, auditEntries, maxId);
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM audits WHERE id <= ?")) {
                statement.setLong(1, maxId);
                statement.execute();
            }
        }
        return auditEntries.size();
    }

    /**
     * Records that a flush run starting at {@code flushStartedAt} emptied the buffer.
     */
    public void markDrained(Instant flushStartedAt) {
        auditFlushStore.markDrained(auditProperties.getNodeName(), flushStartedAt);
    }
}
