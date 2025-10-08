package com.flipkart.grayskull.app.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.spi.AsyncAuditLogger;
import com.flipkart.grayskull.spi.repositories.AuditEntryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.sql.SQLException;

@Configuration
@EnableMongoRepositories(basePackageClasses = AuditCheckpointRepository.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class AuditConfiguration {

    @Bean
    public AsyncAuditLogger derbyAsyncAuditLogger(AuditProperties auditProperties, ObjectMapper objectMapper, MeterRegistry meterRegistry, AuditEntryRepository auditEntryRepository, AuditCheckpointRepository auditCheckpointRepository) throws SQLException {
        return new DerbyAsyncAuditLogger(auditProperties, objectMapper, meterRegistry, auditEntryRepository, auditCheckpointRepository);
    }

    @Bean
    public DerbyAsyncAuditScheduler derbyAsyncAuditScheduler(DerbyAsyncAuditLogger derbyAsyncAuditLogger, MeterRegistry meterRegistry) {
        return new DerbyAsyncAuditScheduler(derbyAsyncAuditLogger, meterRegistry);
    }
}
