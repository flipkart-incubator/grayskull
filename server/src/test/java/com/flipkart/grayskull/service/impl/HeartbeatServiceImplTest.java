package com.flipkart.grayskull.service.impl;

import com.flipkart.grayskull.models.dto.request.HeldSecret;
import com.flipkart.grayskull.service.utils.AuthnUtil;
import com.flipkart.grayskull.spi.models.WorkloadHeartbeat;
import com.flipkart.grayskull.spi.repositories.WorkloadHeartbeatRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("HeartbeatServiceImpl Unit Tests")
class HeartbeatServiceImplTest {

    private final WorkloadHeartbeatRepository repository = mock(WorkloadHeartbeatRepository.class);
    private final AuthnUtil authnUtil = mock(AuthnUtil.class);
    private final HeartbeatServiceImpl service = new HeartbeatServiceImpl(repository, authnUtil);

    @SuppressWarnings("unchecked")
    private List<WorkloadHeartbeat> captureUpsert() {
        ArgumentCaptor<List<WorkloadHeartbeat>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertAll(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("Builds one record per secret with the caller's identity and a shared lastSeen")
    void recordHeartbeat_buildsOneRecordPerSecret() {
        when(authnUtil.getCurrentUsername()).thenReturn("service:payment-api");
        Instant before = Instant.now();

        service.recordHeartbeat("wl", "grayskull-java/0.3.0", List.of(
                new HeldSecret("payments", "db-password", 7, true),
                new HeldSecret("payments", "api-key", 3, false)));

        List<WorkloadHeartbeat> records = captureUpsert();
        assertThat(records).hasSize(2);
        WorkloadHeartbeat first = records.get(0);
        assertThat(first.getWorkload()).isEqualTo("wl");
        assertThat(first.getProjectId()).isEqualTo("payments");
        assertThat(first.getSecretName()).isEqualTo("db-password");
        assertThat(first.getSecretVersion()).isEqualTo(7);
        assertThat(first.getRefreshHook()).isTrue();
        assertThat(first.getSdkVersion()).isEqualTo("grayskull-java/0.3.0");
        assertThat(first.getUserId()).isEqualTo("service:payment-api");
        assertThat(first.getLastSeen()).isBetween(before, Instant.now());
        assertThat(records.get(1).getSecretName()).isEqualTo("api-key");
        assertThat(records.get(1).getRefreshHook()).isFalse();
        assertThat(records.get(1).getLastSeen()).isEqualTo(first.getLastSeen());
    }

    @Test
    @DisplayName("One record per secret version: duplicates merge, different versions stay separate")
    void recordHeartbeat_oneRecordPerSecretVersion() {
        service.recordHeartbeat("wl", null, List.of(
                new HeldSecret("p", "s", 4, false),
                new HeldSecret("p", "s", 4, true),
                new HeldSecret("p", "s", 5, false)));

        List<WorkloadHeartbeat> records = captureUpsert();
        assertThat(records).hasSize(2);
        assertThat(records.get(0).getSecretVersion()).isEqualTo(4);
        assertThat(records.get(0).getRefreshHook()).isTrue();
        assertThat(records.get(1).getSecretVersion()).isEqualTo(5);
    }

    @Test
    @DisplayName("SDK version is truncated to 64 characters")
    void recordHeartbeat_truncatesSdkVersion() {
        service.recordHeartbeat("wl", "x".repeat(100),
                List.of(new HeldSecret("p", "s", 1, false)));

        assertThat(captureUpsert().get(0).getSdkVersion()).hasSize(HeartbeatServiceImpl.MAX_SDK_VERSION_LENGTH);
    }
}
