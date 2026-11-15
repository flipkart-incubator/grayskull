package com.flipkart.grayskull.service.impl;

import com.flipkart.grayskull.models.dto.request.HeldSecret;
import com.flipkart.grayskull.service.utils.AuthnUtil;
import com.flipkart.grayskull.spi.models.WorkloadHeartbeat;
import com.flipkart.grayskull.spi.repositories.WorkloadHeartbeatRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
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

        service.recordHeartbeat("wl", "grayskull-java/0.3.0", "10.1.2.3", List.of(
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
        assertThat(first.getAddress()).isEqualTo("10.1.2.3");
        assertThat(first.getLastSeen()).isBetween(before, Instant.now());
        assertThat(records.get(1).getSecretName()).isEqualTo("api-key");
        assertThat(records.get(1).getRefreshHook()).isFalse();
        assertThat(records.get(1).getLastSeen()).isEqualTo(first.getLastSeen());
    }

    @Test
    @DisplayName("One record per secret version: duplicates merge, different versions stay separate")
    void recordHeartbeat_oneRecordPerSecretVersion() {
        service.recordHeartbeat("wl", null, "10.1.2.3", List.of(
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
        service.recordHeartbeat("wl", "x".repeat(100), "10.1.2.3",
                List.of(new HeldSecret("p", "s", 1, false)));

        assertThat(captureUpsert().get(0).getSdkVersion()).hasSize(HeartbeatServiceImpl.MAX_SDK_VERSION_LENGTH);
    }

    // ---- fetch sightings ----

    /** A clock the test moves forward. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-11-12T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private final TestClock clock = new TestClock();
    private final HeartbeatServiceImpl sightings = new HeartbeatServiceImpl(repository, authnUtil, clock);

    @SuppressWarnings("unchecked")
    private List<WorkloadHeartbeat> allUpserted() {
        ArgumentCaptor<List<WorkloadHeartbeat>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository, atLeast(0)).upsertAll(captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream).toList();
    }

    @Test
    @DisplayName("A fetch is written synchronously with caller, address, SDK and served version, without refreshHook")
    void recordFetch_writesRecord() {
        when(authnUtil.getCurrentUsername()).thenReturn("service:payment-api");

        sightings.recordFetch("wl", "grayskull-java/0.3.0", "10.1.2.3", "payments", "db-password", 7);

        List<WorkloadHeartbeat> records = allUpserted();
        assertThat(records).hasSize(1);
        WorkloadHeartbeat r = records.get(0);
        assertThat(r.getWorkload()).isEqualTo("wl");
        assertThat(r.getAddress()).isEqualTo("10.1.2.3");
        assertThat(r.getProjectId()).isEqualTo("payments");
        assertThat(r.getSecretName()).isEqualTo("db-password");
        assertThat(r.getSecretVersion()).isEqualTo(7);
        assertThat(r.getSdkVersion()).isEqualTo("grayskull-java/0.3.0");
        assertThat(r.getUserId()).isEqualTo("service:payment-api");
        assertThat(r.getLastSeen()).isEqualTo(clock.instant());
        assertThat(r.getRefreshHook()).isNull();
    }

    @Test
    @DisplayName("A hook poll is recorded like a fetch, with refreshHook = true")
    void recordHookPoll_writesRecordWithRefreshHook() {
        sightings.recordHookPoll("wl", "grayskull-java/0.3.0", "10.1.2.3", "payments", "db-password", 8);

        List<WorkloadHeartbeat> records = allUpserted();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getSecretVersion()).isEqualTo(8);
        assertThat(records.get(0).getRefreshHook()).isTrue();
    }

    @Test
    @DisplayName("No workload header (old SDK, raw API call) or an oversized one: nothing is recorded")
    void recordFetch_withoutUsableWorkload_writesNothing() {
        sightings.recordFetch(null, null, "10.1.2.3", "p", "s", 1);
        sightings.recordFetch("  ", null, "10.1.2.3", "p", "s", 1);
        sightings.recordFetch("x".repeat(HeartbeatServiceImpl.MAX_WORKLOAD_LENGTH + 1), null, "10.1.2.3", "p", "s", 1);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("Deduped: the same client and secret version is written once per hour; anything new at once")
    void recordFetch_isDedupedPerClientAndVersion() {
        sightings.recordFetch("wl", null, "10.0.0.1", "p", "s", 1);
        sightings.recordFetch("wl", null, "10.0.0.1", "p", "s", 1);    // within the hour: skipped
        clock.advance(Duration.ofMinutes(59));
        sightings.recordFetch("wl", null, "10.0.0.1", "p", "s", 1);    // still within the hour: skipped
        sightings.recordFetch("wl", null, "10.0.0.1", "p", "s", 2);    // new version: written
        sightings.recordFetch("wl", null, "10.0.0.2", "p", "s", 1);    // other pod of the workload: written
        sightings.recordFetch("other", null, "10.0.0.1", "p", "s", 1); // other workload: written
        clock.advance(Duration.ofMinutes(1));
        sightings.recordFetch("wl", null, "10.0.0.1", "p", "s", 1);    // an hour later: written again

        assertThat(allUpserted()).extracting(WorkloadHeartbeat::getWorkload, WorkloadHeartbeat::getAddress,
                        WorkloadHeartbeat::getSecretVersion)
                .containsExactly(tuple("wl", "10.0.0.1", 1), tuple("wl", "10.0.0.1", 2), tuple("wl", "10.0.0.2", 1),
                        tuple("other", "10.0.0.1", 1), tuple("wl", "10.0.0.1", 1));
    }

    @Test
    @DisplayName("A failed write never reaches the caller, and the next request tries again")
    void recordFetch_failureIsSwallowedAndRetriedOnNextRequest() {
        doThrow(new RuntimeException("mongo down")).doNothing().when(repository).upsertAll(anyList());

        sightings.recordFetch("wl", null, "10.1.2.3", "p", "s", 1);
        sightings.recordFetch("wl", null, "10.1.2.3", "p", "s", 1);

        verify(repository, times(2)).upsertAll(anyList());
    }
}
