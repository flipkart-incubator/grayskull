package com.flipkart.grayskull.spimpl.audit;

import com.flipkart.grayskull.spi.AsyncAuditLogger;
import com.flipkart.grayskull.spi.models.AuditEntry;
import com.flipkart.grayskull.spi.repositories.AuditEntryRepository;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@AllArgsConstructor
public class SimpleAsyncAuditLogger implements AsyncAuditLogger {

    private final AuditEntryRepository auditEntryRepository;
    private final ExecutorService executorService = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("async-audit-logger").factory());

    @Override
    public void log(AuditEntry auditEntry) {
        executorService.submit(() -> auditEntryRepository.save(auditEntry));
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
    }
}
