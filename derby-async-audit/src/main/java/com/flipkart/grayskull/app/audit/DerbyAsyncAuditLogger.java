package com.flipkart.grayskull.app.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.spi.AsyncAuditLogger;
import com.flipkart.grayskull.spi.models.AuditEntry;
import com.flipkart.grayskull.spi.repositories.AuditEntryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
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
    private final AuditEntryRepository auditEntryRepository;
    private final AuditCheckpointRepository auditCheckpointRepository;

    public DerbyAsyncAuditLogger(AuditProperties auditProperties, ObjectMapper objectMapper, MeterRegistry meterRegistry, AuditEntryRepository auditEntryRepository, AuditCheckpointRepository auditCheckpointRepository) throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:derby:" + auditProperties.getDerbyDirectory() + ";create=true");
        this.auditProperties = auditProperties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.auditEntryRepository = auditEntryRepository;
        this.auditCheckpointRepository = auditCheckpointRepository;
    }

    @PostConstruct
    public void init() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE audits (id BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY(START WITH 1, INCREMENT BY 1), event LONG VARCHAR)");
        } catch (SQLException e) {
            if (!TABLE_ALREADY_EXISTS.equals(e.getSQLState())) {
                throw e;
            }
        }
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

    @Transactional
    public int commitBatchToDb() throws SQLException, JsonProcessingException {
        AuditCheckpoint auditCheckpoint = auditCheckpointRepository.findByNodeName(auditProperties.getNodeName()).orElseGet(() -> new AuditCheckpoint(auditProperties.getNodeName()));
        long maxId = auditCheckpoint.getLogId();
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
                auditEntry.getMetadata().put("logId", auditProperties.getNodeName() + "." + maxId + "." + auditEntry.getTimestamp());
                auditEntries.add(auditEntry);
            }
        }
        log.info("found {} entries. storing them to db. current checkpoint is {}", auditEntries.size(), maxId);
        if (!auditEntries.isEmpty()) {
            auditEntryRepository.saveAll(auditEntries);
            auditCheckpoint.setLogId(maxId);
            auditCheckpointRepository.save(auditCheckpoint);
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM audits WHERE id <= ?")) {
                statement.setLong(1, maxId);
                statement.execute();
            }
        }
        return auditEntries.size();
    }
}
