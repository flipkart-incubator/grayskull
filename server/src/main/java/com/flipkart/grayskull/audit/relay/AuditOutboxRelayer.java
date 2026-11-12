package com.flipkart.grayskull.audit.relay;

import com.flipkart.grayskull.audit.mysql.MysqlAuditStore;
import com.flipkart.grayskull.entities.AuditOutboxEntity;
import com.flipkart.grayskull.spimpl.repositories.mongo.AuditOutboxMongoRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Copies write audits from the MongoDB outbox to MySQL, then purges old SYNCED entries. Runs on one pod at a time
 * (ShedLock).
 * <p>
 * Each batch is inserted in one MySQL transaction and marked SYNCED only after it commits. If anything fails, the
 * run stops: the MySQL transaction is rolled back, the entries stay PENDING and the next run retries them. A crash
 * between the commit and the SYNCED update only re-sends the batch, and the insert skips rows that already exist.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditOutboxRelayer {

    static final String JOB_NAME = "auditOutboxRelayer";
    private final AuditOutboxMongoRepository outboxRepository;
    private final MysqlAuditStore auditStore;
    private final AuditRelayerProperties properties;
    private final MeterRegistry meterRegistry;

    @Scheduled(cron = "${grayskull.audit.relayer.cron:0 * * * * *}")
    @SchedulerLock(name = JOB_NAME, lockAtMostFor = "PT6M", lockAtLeastFor = "${grayskull.audit.relayer.lock-at-least-for:PT30S}")
    public void tick() {
        if (!properties.isEnabled()) {
            log.debug("Audit outbox relayer is disabled");
            return;
        }

        try {
            drainPending();
        } catch (Exception e) {
            meterRegistry.counter("audit.relayer.failures").increment();
            log.error("Audit outbox relayer drain failed; the remaining PENDING entries are retried in the next run", e);
        }

        try {
            purgeOldSynced();
        } catch (Exception e) {
            log.error("Audit outbox relayer purge failed", e);
        }
    }

    private void drainPending() {
        Instant deadline = Instant.now().plus(properties.getMaxRuntime());
        long synced = 0;
        while (Instant.now().isBefore(deadline)) {
            List<AuditOutboxEntity> batch = outboxRepository.findPending(properties.getBatchSize());
            if (batch.isEmpty()) {
                break;
            }
            auditStore.insertMissing(batch);
            long marked = outboxRepository.markSynced(batch.stream().map(AuditOutboxEntity::getId).toList(), Instant.now());
            synced += marked;
            meterRegistry.counter("audit.relayer.synced").increment(marked);
            if (batch.size() < properties.getBatchSize()) {
                break;
            }
        }
        if (!Instant.now().isBefore(deadline)) {
            log.info("Audit outbox relayer hit max runtime {}; the rest is left for the next run", properties.getMaxRuntime());
        }
        if (synced > 0) {
            log.info("Audit outbox relayer drain complete synced={}", synced);
        }
    }

    private void purgeOldSynced() {
        Instant cutoff = Instant.now().minus(properties.getPurgeAfter());
        long purged = outboxRepository.deleteSyncedBefore(cutoff);
        if (purged > 0) {
            meterRegistry.counter("audit.relayer.purged").increment(purged);
            log.info("Audit outbox relayer purge complete purged={} cutoff={}", purged, cutoff);
        }
    }
}
