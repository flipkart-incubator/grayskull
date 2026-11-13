package com.flipkart.grayskull.app.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.spi.models.AuditEntry;
import com.flipkart.grayskull.spi.AuditFlushStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;


import static com.flipkart.grayskull.app.audit.DerbyAsyncAuditLogger.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DerbyAsyncAuditLoggerTest {

    private final AuditProperties auditProperties = new AuditProperties("memory:testdb", "test-node", 100, "1m", Duration.ZERO);
    private final Connection connection = mock();
    private final ObjectMapper objectMapper = mock();
    private final MeterRegistry meterRegistry = mock();
    private final AuditFlushStore auditFlushStore = mock();
    private final Statement statement = mock();
    private final PreparedStatement preparedStatement = mock();
    private final ResultSet resultSet = mock();
    private final Counter counter = mock();

    private DerbyAsyncAuditLogger logger;

    @BeforeEach
    void setUp() throws SQLException {
        logger = new DerbyAsyncAuditLogger(auditProperties, objectMapper, meterRegistry, auditFlushStore);
        ReflectionTestUtils.setField(logger, "connection", connection);
    }

    @Test
    void testInit_CreatesTable() throws SQLException {
        when(connection.createStatement()).thenReturn(statement);

        logger.init();

        verify(statement).execute("CREATE TABLE audits (id BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY(START WITH 1, INCREMENT BY 1), event LONG VARCHAR)");
    }

    @Test
    void testInit_TableAlreadyExists_DoesNotThrow() throws SQLException {
        when(connection.createStatement()).thenReturn(statement);
        SQLException e = new SQLException("Table exists", "X0Y32");
        doThrow(e).when(statement).execute(anyString());

        assertDoesNotThrow(() -> logger.init());
    }

    @Test
    void testLog_Success() throws SQLException, JsonProcessingException {
        AuditEntry auditEntry = createTestAuditEntry();
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(objectMapper.writeValueAsString(auditEntry)).thenReturn("{\"test\":\"data\"}");

        logger.log(auditEntry);

        verify(preparedStatement).setString(1, "{\"test\":\"data\"}");
        verify(preparedStatement).execute();
    }

    @Test
    void testLog_JsonProcessingException() throws SQLException, JsonProcessingException {
        AuditEntry auditEntry = createTestAuditEntry();
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(objectMapper.writeValueAsString(auditEntry)).thenThrow(new JsonProcessingException("JSON error") {});
        when(meterRegistry.counter(AUDIT_ERROR_METRIC, ACTION_TAG, "serialize", EXCEPTION_TAG, "JsonProcessingException")).thenReturn(counter);

        logger.log(auditEntry);

        verify(counter).increment();
    }

    @Test
    void testLog_SQLException() throws SQLException {
        AuditEntry auditEntry = createTestAuditEntry();
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("DB error"));
        when(meterRegistry.counter(AUDIT_ERROR_METRIC, ACTION_TAG, "log", EXCEPTION_TAG, "SQLException")).thenReturn(counter);

        logger.log(auditEntry);

        verify(counter).increment();
    }

    @Test
    void testCommitBatchToDb_Success() throws SQLException, JsonProcessingException {
        PreparedStatement selectStatement = mock();
        PreparedStatement deleteStatement = mock();

        when(auditFlushStore.lastFlushedId("test-node")).thenReturn(0L);
        when(connection.prepareStatement("SELECT id, event FROM audits WHERE id > ? ORDER BY id FETCH FIRST ? ROWS ONLY")).thenReturn(selectStatement);
        when(connection.prepareStatement("DELETE FROM audits WHERE id <= ?")).thenReturn(deleteStatement);
        when(selectStatement.getResultSet()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true).thenReturn(false);
        when(resultSet.getLong(1)).thenReturn(1L);
        when(resultSet.getString(2)).thenReturn("{\"test\":\"data\"}");
        when(objectMapper.readValue(anyString(), eq(AuditEntry.class))).thenReturn(createTestAuditEntry());

        int result = logger.commitBatchToDb();

        assertEquals(1, result);
        verify(auditFlushStore).commitBatch(eq("test-node"), anyList(), eq(1L));
        verify(deleteStatement).setLong(1, 1L);
        verify(deleteStatement).execute();
    }

    @Test
    void testCommitBatchToDb_NoEntries() throws SQLException, JsonProcessingException {
        PreparedStatement selectStatement = mock();

        when(auditFlushStore.lastFlushedId("test-node")).thenReturn(0L);
        when(connection.prepareStatement("SELECT id, event FROM audits WHERE id > ? ORDER BY id FETCH FIRST ? ROWS ONLY")).thenReturn(selectStatement);
        when(selectStatement.getResultSet()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        int result = logger.commitBatchToDb();

        assertEquals(0, result);
        verify(auditFlushStore, never()).commitBatch(anyString(), anyList(), anyLong());
        verify(connection, never()).prepareStatement("DELETE FROM audits WHERE id <= ?");
    }

    @Test
    void testCommitBatchToDb_StoreFails_DerbyRowsAreKept() throws SQLException, JsonProcessingException {
        PreparedStatement selectStatement = mock();

        when(auditFlushStore.lastFlushedId("test-node")).thenReturn(4L);
        when(connection.prepareStatement("SELECT id, event FROM audits WHERE id > ? ORDER BY id FETCH FIRST ? ROWS ONLY")).thenReturn(selectStatement);
        when(selectStatement.getResultSet()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true).thenReturn(false);
        when(resultSet.getLong(1)).thenReturn(5L);
        when(resultSet.getString(2)).thenReturn("{\"test\":\"data\"}");
        when(objectMapper.readValue(anyString(), eq(AuditEntry.class))).thenReturn(createTestAuditEntry());
        doThrow(new IllegalStateException("store down")).when(auditFlushStore).commitBatch(anyString(), anyList(), anyLong());

        assertThrows(IllegalStateException.class, () -> logger.commitBatchToDb());

        verify(selectStatement).setLong(1, 4L);
        verify(connection, never()).prepareStatement("DELETE FROM audits WHERE id <= ?");
    }

    @Test
    void testMarkDrained_DelegatesToStore() {
        Instant startedAt = Instant.now();

        logger.markDrained(startedAt);

        verify(auditFlushStore).markDrained("test-node", startedAt);
    }

    private AuditEntry createTestAuditEntry() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("test", "value");
        return new AuditEntry(null, "project1", "SECRET", "secret1", 1, "READ", "user1", null, Map.of("ip", "ip1"), null, metadata);
    }
}