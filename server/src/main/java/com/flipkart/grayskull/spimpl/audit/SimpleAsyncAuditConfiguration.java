package com.flipkart.grayskull.spimpl.audit;

import com.flipkart.grayskull.spi.AsyncAuditLogger;
import com.flipkart.grayskull.spi.repositories.AuditEntryRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnMissingBean(AsyncAuditLogger.class)
public class SimpleAsyncAuditConfiguration {

    @Bean
    public AsyncAuditLogger simpleAsyncAuditLogger(AuditEntryRepository auditEntryRepository) {
        return new SimpleAsyncAuditLogger(auditEntryRepository);
    }
}
