package com.flipkart.grayskull.app.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.SQLException;

import static com.flipkart.grayskull.app.audit.DerbyAsyncAuditLogger.AUDIT_ERROR_METRIC;
import static com.flipkart.grayskull.app.audit.DerbyAsyncAuditLogger.ACTION_TAG;
import static com.flipkart.grayskull.app.audit.DerbyAsyncAuditLogger.EXCEPTION_TAG;

@AllArgsConstructor
@Slf4j
public class DerbyAsyncAuditScheduler {
    private final DerbyAsyncAuditLogger derbyAsyncAuditLogger;
    private final MeterRegistry meterRegistry;

    @Scheduled(fixedDelayString = "${audit.batch-time-interval}")
    public void run() {
        try {
            int committed;
            do {
                committed = derbyAsyncAuditLogger.commitBatchToDb();
            } while (committed != 0);
        }  catch (JsonProcessingException e) {
            log.error("Failed to deserialize audit entry", e);
            meterRegistry.counter(AUDIT_ERROR_METRIC, ACTION_TAG, "deserialize", EXCEPTION_TAG, "JsonProcessingException").increment();
        } catch (SQLException e) {
            log.error("Failed to connect to Derby", e);
            meterRegistry.counter(AUDIT_ERROR_METRIC, ACTION_TAG, "fetch-logs", EXCEPTION_TAG, "SQLException").increment();
        }

    }
}
