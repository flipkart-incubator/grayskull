package com.flipkart.grayskull.app.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static com.flipkart.grayskull.app.audit.DerbyAsyncAuditLogger.*;
import static org.mockito.Mockito.*;

class DerbyAsyncAuditSchedulerTest {

    private final DerbyAsyncAuditLogger derbyAsyncAuditLogger = mock(DerbyAsyncAuditLogger.class);
    private final MeterRegistry meterRegistry = mock(MeterRegistry.class);
    private final Counter counter = mock(Counter.class);

    private final DerbyAsyncAuditScheduler scheduler = new DerbyAsyncAuditScheduler(derbyAsyncAuditLogger, meterRegistry);

    @Test
    void testRun_Success() throws SQLException, JsonProcessingException {
        when(derbyAsyncAuditLogger.commitBatchToDb()).thenReturn(5).thenReturn(3).thenReturn(0);

        scheduler.run();

        verify(derbyAsyncAuditLogger, times(3)).commitBatchToDb();
    }

    @Test
    void testRun_JsonProcessingException() throws SQLException, JsonProcessingException {
        JsonProcessingException exception = new JsonProcessingException("JSON error") {};
        when(derbyAsyncAuditLogger.commitBatchToDb()).thenThrow(exception);
        when(meterRegistry.counter(AUDIT_ERROR_METRIC, ACTION_TAG, "deserialize", EXCEPTION_TAG, "JsonProcessingException")).thenReturn(counter);

        scheduler.run();

        verify(counter).increment();
    }

    @Test
    void testRun_SQLException() throws SQLException, JsonProcessingException {
        SQLException exception = new SQLException("DB error");
        when(derbyAsyncAuditLogger.commitBatchToDb()).thenThrow(exception);
        when(meterRegistry.counter(AUDIT_ERROR_METRIC, ACTION_TAG, "fetch-logs", EXCEPTION_TAG, "SQLException")).thenReturn(counter);

        scheduler.run();

        verify(counter).increment();
    }

    @Test
    void testRun_NoEntries() throws SQLException, JsonProcessingException {
        when(derbyAsyncAuditLogger.commitBatchToDb()).thenReturn(0);

        scheduler.run();

        verify(derbyAsyncAuditLogger, times(1)).commitBatchToDb();
    }
}