package com.flipkart.grayskull.app.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.spi.AsyncAuditLogger;
import com.flipkart.grayskull.spi.AuditFlushStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Buffers read audits in a local Derby database and flushes them to the {@link AuditFlushStore} provided by the
 * application (the server's MySQL audit store).
 */
@Configuration
@ConfigurationPropertiesScan
@EnableScheduling
public class AuditConfiguration {

    @Bean
    @Primary
    public AsyncAuditLogger derbyAsyncAuditLogger(AuditProperties auditProperties, ObjectMapper objectMapper, MeterRegistry meterRegistry, AuditFlushStore auditFlushStore) throws SQLException, IOException {
        new DerbyStaleDataCleaner(auditFlushStore, auditProperties).cleanStaleData();
        return new DerbyAsyncAuditLogger(auditProperties, objectMapper, meterRegistry, auditFlushStore);
    }

    @Bean
    public DerbyAsyncAuditScheduler derbyAsyncAuditScheduler(DerbyAsyncAuditLogger derbyAsyncAuditLogger, MeterRegistry meterRegistry) {
        return new DerbyAsyncAuditScheduler(derbyAsyncAuditLogger, meterRegistry);
    }
}
