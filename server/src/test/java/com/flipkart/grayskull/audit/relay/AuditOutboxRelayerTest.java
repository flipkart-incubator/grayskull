package com.flipkart.grayskull.audit.relay;

import com.flipkart.grayskull.audit.mysql.MysqlAuditStore;
import com.flipkart.grayskull.entities.AuditOutboxEntity;
import com.flipkart.grayskull.entities.AuditSyncStatus;
import com.flipkart.grayskull.spimpl.repositories.mongo.AuditOutboxMongoRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuditOutboxRelayerTest {

    private final AuditOutboxMongoRepository outboxRepository = mock();
    private final MysqlAuditStore auditStore = mock();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AuditRelayerProperties properties = new AuditRelayerProperties();
    private AuditOutboxRelayer relayer;

    @BeforeEach
    void setUp() {
        properties.setBatchSize(2);
        relayer = new AuditOutboxRelayer(outboxRepository, auditStore, properties, meterRegistry);
        when(outboxRepository.markSynced(anyCollection(), any())).thenAnswer(inv -> (long) ((Collection<?>) inv.getArgument(0)).size());
    }

    @Test
    void tick_disabled_doesNothing() {
        properties.setEnabled(false);

        relayer.tick();

        verifyNoInteractions(outboxRepository, auditStore);
    }

    @Test
    void tick_copiesBatchesUntilEmpty_thenMarksSyncedAndPurges() {
        AuditOutboxEntity a = entry("a");
        AuditOutboxEntity b = entry("b");
        AuditOutboxEntity c = entry("c");
        when(outboxRepository.findPending(2)).thenReturn(List.of(a, b)).thenReturn(List.of(c));
        when(outboxRepository.deleteSyncedBefore(any())).thenReturn(4L);

        relayer.tick();

        verify(auditStore).insertMissing(List.of(a, b));
        verify(auditStore).insertMissing(List.of(c));
        verify(outboxRepository).markSynced(eq(List.of("a", "b")), any());
        verify(outboxRepository).markSynced(eq(List.of("c")), any());
        assertThat(meterRegistry.counter("audit.relayer.runs").count()).isEqualTo(1);
        assertThat(meterRegistry.counter("audit.relayer.synced").count()).isEqualTo(3);
        assertThat(meterRegistry.counter("audit.relayer.purged").count()).isEqualTo(4);
    }

    @Test
    void tick_insertFailure_leavesBatchPendingForNextRun_andStillPurges() {
        AuditOutboxEntity a = entry("a");
        when(outboxRepository.findPending(2)).thenReturn(List.of(a));
        doThrow(new DataAccessResourceFailureException("mysql down")).when(auditStore).insertMissing(anyCollection());

        relayer.tick();

        verify(outboxRepository, never()).markSynced(anyCollection(), any());
        verify(outboxRepository).deleteSyncedBefore(any());
        assertThat(meterRegistry.counter("audit.relayer.failures").count()).isEqualTo(1);
    }

    @Test
    void tick_maxRuntimeReached_leavesTheRestForTheNextRun() {
        properties.setMaxRuntime(Duration.ZERO);

        relayer.tick();

        verify(outboxRepository, never()).findPending(anyInt());
        verify(outboxRepository).deleteSyncedBefore(any());
    }

    @Test
    void tick_purgeFailure_doesNotFailTheDrain() {
        when(outboxRepository.findPending(2)).thenReturn(List.of());
        when(outboxRepository.deleteSyncedBefore(any())).thenThrow(new DataAccessResourceFailureException("mongo down"));

        relayer.tick();

        assertThat(meterRegistry.counter("audit.relayer.failures").count()).isZero();
    }

    private static AuditOutboxEntity entry(String id) {
        return AuditOutboxEntity.builder().id(id).projectId("p").action("CREATE_SECRET")
                .timestamp(Instant.parse("2026-10-01T00:00:00Z")).syncStatus(AuditSyncStatus.PENDING).build();
    }
}
