package com.flipkart.grayskull.audit.outbox;

import com.flipkart.grayskull.entities.AuditOutboxEntity;
import com.flipkart.grayskull.entities.AuditSyncStatus;
import com.flipkart.grayskull.mappers.AuditOutboxMapper;
import com.flipkart.grayskull.spi.models.AuditEntry;
import com.flipkart.grayskull.spimpl.repositories.mongo.AuditOutboxMongoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OutboxWriteAuditRecorderTest {

    private final AuditOutboxMongoRepository repository = mock();
    private final OutboxWriteAuditRecorder recorder = new OutboxWriteAuditRecorder(repository, Mappers.getMapper(AuditOutboxMapper.class));

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void record_outsideTransaction_fails() {
        AuditEntry entry = AuditEntry.builder().projectId("p").build();

        assertThrows(IllegalStateException.class, () -> recorder.record(entry));
        verify(repository, never()).insert(any(AuditOutboxEntity.class));
    }

    @Test
    void record_insideTransaction_insertsPendingCopy() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        AuditEntry entry = AuditEntry.builder().projectId("p").resourceName("s").action("CREATE_SECRET")
                .userId("user").metadata(Map.of("k", "v")).build();

        recorder.record(entry);

        ArgumentCaptor<AuditOutboxEntity> captor = ArgumentCaptor.forClass(AuditOutboxEntity.class);
        verify(repository).insert(captor.capture());
        AuditOutboxEntity saved = captor.getValue();
        assertThat(saved.getSyncStatus()).isEqualTo(AuditSyncStatus.PENDING);
        assertThat(saved.getId()).isNull();
        assertThat(saved.getProjectId()).isEqualTo("p");
        assertThat(saved.getResourceName()).isEqualTo("s");
        assertThat(saved.getTimestamp()).isEqualTo(entry.getTimestamp());
        assertThat(saved.getMetadata()).containsEntry("k", "v");
    }
}
