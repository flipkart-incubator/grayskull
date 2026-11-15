package com.flipkart.grayskull.spimpl.repositories;

import com.flipkart.grayskull.entities.WorkloadHeartbeatEntity;
import com.flipkart.grayskull.spi.models.WorkloadHeartbeat;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("WorkloadHeartbeatRepositoryImpl Unit Tests")
class WorkloadHeartbeatRepositoryImplTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final BulkOperations bulk = mock(BulkOperations.class);
    private final WorkloadHeartbeatRepositoryImpl repository = new WorkloadHeartbeatRepositoryImpl(mongoTemplate);

    @Test
    @DisplayName("Upserts each record on (workload, projectId, secretName, secretVersion) in one unordered bulk")
    void upsertAll_upsertsOnUniqueKey() {
        when(mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, WorkloadHeartbeatEntity.class)).thenReturn(bulk);
        Instant now = Instant.parse("2026-11-12T10:15:00Z");
        WorkloadHeartbeat heartbeat = WorkloadHeartbeat.builder()
                .workload("wl")
                .projectId("payments").secretName("db-password").secretVersion(7).refreshHook(true)
                .sdkVersion("grayskull-java/0.3.0").userId("service:payment-api").lastSeen(now)
                .build();

        repository.upsertAll(List.of(heartbeat, heartbeat.toBuilder().secretVersion(6).build()));

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(bulk, times(2)).upsert(query.capture(), update.capture());
        verify(bulk).execute();

        assertThat(query.getAllValues().get(0).getQueryObject()).isEqualTo(new Document()
                .append("workload", "wl").append("projectId", "payments")
                .append("secretName", "db-password").append("secretVersion", 7));
        assertThat(query.getAllValues().get(1).getQueryObject().get("secretVersion")).isEqualTo(6);
        Document set = (Document) update.getAllValues().get(0).getUpdateObject().get("$set");
        assertThat(set).containsEntry("refreshHook", true)
                .containsEntry("sdkVersion", "grayskull-java/0.3.0")
                .containsEntry("userId", "service:payment-api")
                .containsEntry("lastSeen", now);
        Document setOnInsert = (Document) update.getAllValues().get(0).getUpdateObject().get("$setOnInsert");
        assertThat(setOnInsert).containsEntry("firstSeen", now);
    }

    @Test
    @DisplayName("A fetch record (refreshHook null) leaves the stored refreshHook untouched")
    void upsertAll_nullRefreshHookIsNotSet() {
        when(mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, WorkloadHeartbeatEntity.class)).thenReturn(bulk);
        WorkloadHeartbeat fetch = WorkloadHeartbeat.builder()
                .workload("wl").projectId("p").secretName("s").secretVersion(1)
                .lastSeen(Instant.parse("2026-11-12T10:15:00Z"))
                .build();

        repository.upsertAll(List.of(fetch));

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(bulk).upsert(any(Query.class), update.capture());
        Document set = (Document) update.getValue().getUpdateObject().get("$set");
        assertThat(set).doesNotContainKey("refreshHook").containsKey("lastSeen");
    }

    @Test
    @DisplayName("Does nothing for an empty list")
    void upsertAll_emptyIsNoop() {
        repository.upsertAll(List.of());

        verify(mongoTemplate, never()).bulkOps(any(BulkOperations.BulkMode.class), any(Class.class));
    }
}
