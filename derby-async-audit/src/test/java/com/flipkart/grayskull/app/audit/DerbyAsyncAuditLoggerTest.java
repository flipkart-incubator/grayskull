package com.flipkart.grayskull.app.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.flipkart.grayskull.spi.models.AuditEntry;
import com.flipkart.grayskull.spi.repositories.AuditEntryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.flipkart.grayskull.app.audit.DerbyAsyncAuditLogger.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DerbyAsyncAuditLoggerTest {

    private final AuditProperties auditProperties = new AuditProperties("memory:testdb", "test-node", 100, "1m", Duration.ZERO);
    private final DerbyDao derbyDao = mock();
    private final MeterRegistry meterRegistry = mock();
    private final AuditEntryRepository auditEntryRepository = mock();
    private final AuditCheckpointRepository auditCheckpointRepository = mock();
    private final Counter counter = mock();

    private DerbyAsyncAuditLogger logger;

    @BeforeEach
    void setUp() {
        logger = new DerbyAsyncAuditLogger(auditProperties, derbyDao, meterRegistry, auditEntryRepository, auditCheckpointRepository);
    }

    @Test
    void testLog_Success() throws SQLException, JsonProcessingException {
        AuditEntry auditEntry = createTestAuditEntry();

        logger.log(auditEntry);

        verify(derbyDao).insertAuditEntry(auditEntry);
    }

    @Test
    void testLog_JsonProcessingException() throws SQLException, JsonProcessingException {
        AuditEntry auditEntry = createTestAuditEntry();
        doThrow(JsonProcessingException.class).when(derbyDao).insertAuditEntry(auditEntry);
        when(meterRegistry.counter(AUDIT_ERROR_METRIC, ACTION_TAG, "serialize", EXCEPTION_TAG, "JsonProcessingException")).thenReturn(counter);

        logger.log(auditEntry);

        verify(counter).increment();
    }

    @Test
    void testLog_SQLException() throws SQLException, JsonProcessingException {
        AuditEntry auditEntry = createTestAuditEntry();
        doThrow(SQLException.class).when(derbyDao).insertAuditEntry(auditEntry);
        when(meterRegistry.counter(AUDIT_ERROR_METRIC, ACTION_TAG, "log", EXCEPTION_TAG, "SQLException")).thenReturn(counter);

        logger.log(auditEntry);

        verify(counter).increment();
    }

    @Test
    void testCommitBatchToDb_Success() throws SQLException, JsonProcessingException {
        AuditCheckpoint checkpoint = new AuditCheckpoint("test-node");
        checkpoint.setLogId(0L);

        when(auditCheckpointRepository.findByNodeName("test-node")).thenReturn(Optional.of(checkpoint));
        when(derbyDao.fetchAuditEntries(0, auditProperties.getBatchSize())).thenReturn(Map.of(1L, createTestAuditEntry()));

        int result = logger.commitBatchToDb();

        assertEquals(1, result);
        verify(auditEntryRepository).saveAll(anyCollection());
        verify(auditCheckpointRepository).save(checkpoint);
    }

    @Test
    void testCommitBatchToDb_NoEntries() throws SQLException, JsonProcessingException {
        AuditCheckpoint checkpoint = new AuditCheckpoint("test-node");
        checkpoint.setLogId(0L);

        when(auditCheckpointRepository.findByNodeName("test-node")).thenReturn(Optional.of(checkpoint));
        when(derbyDao.fetchAuditEntries(0, auditProperties.getBatchSize())).thenReturn(Map.of());

        int result = logger.commitBatchToDb();

        assertEquals(0, result);
        verify(auditEntryRepository, never()).saveAll(anyCollection());
        verify(derbyDao, never()).deleteAuditEntries(anyLong());
    }

    private AuditEntry createTestAuditEntry() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("test", "value");
        return new AuditEntry(null, "project1", "SECRET", "secret1", 1, "READ", "user1", Map.of("ip", "ip1"), null, metadata);
    }
}