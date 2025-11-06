package com.flipkart.grayskull.app.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.spi.AsyncAuditLogger;
import com.flipkart.grayskull.spi.repositories.AuditEntryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.sql.SQLException;

@Configuration
@EnableMongoRepositories(basePackageClasses = AuditCheckpointRepository.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class AuditConfiguration {

    @Bean
    public DerbyDao derbyDao(AuditProperties auditProperties, ObjectMapper objectMapper) throws SQLException {
        return new DerbyDao(auditProperties.getDerbyUrl(), objectMapper);
    }

    @Bean
    @Primary
    public AsyncAuditLogger derbyAsyncAuditLogger(AuditProperties auditProperties, DerbyDao derbyDao, MeterRegistry meterRegistry, AuditEntryRepository auditEntryRepository, AuditCheckpointRepository auditCheckpointRepository) throws IOException {
        new DerbyStaleDataCleaner(auditCheckpointRepository, auditProperties).cleanStaleData();
        return new DerbyAsyncAuditLogger(auditProperties, derbyDao, meterRegistry, auditEntryRepository, auditCheckpointRepository);
    }

    @Bean
    public DerbyAsyncAuditScheduler derbyAsyncAuditScheduler(DerbyAsyncAuditLogger derbyAsyncAuditLogger, MeterRegistry meterRegistry) {
        return new DerbyAsyncAuditScheduler(derbyAsyncAuditLogger, meterRegistry);
    }
}
