package com.flipkart.grayskull.audit.mysql;

import com.flipkart.grayskull.BaseIntegrationTest;
import com.flipkart.grayskull.audit.mysql.entity.AuditEntryRow;
import com.flipkart.grayskull.audit.mysql.repository.AuditEntryRowRepository;
import com.flipkart.grayskull.audit.relay.AuditOutboxRelayer;
import com.flipkart.grayskull.entities.AuditOutboxEntity;
import com.flipkart.grayskull.entities.AuditSyncStatus;
import com.flipkart.grayskull.spi.models.AuditEntry;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end check of the MySQL audit store against real MySQL and MongoDB containers:
 * outbox + relayer, the Derby flush target, idempotent inserts and cursor paging.
 */
class MysqlAuditStorageIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MongoTemplate mongoTemplate;
    @Autowired
    private AuditOutboxRelayer relayer;
    @Autowired
    private AuditEntryRowRepository rowRepository;
    @Autowired
    private MysqlAuditStore store;
    @Autowired
    private MysqlAuditFlushStore flushStore;

    @Test
    void writeAudit_goesThroughOutbox_andRelayerCopiesItOnce() throws Exception {
        String project = "mysql-audit-" + UUID.randomUUID();
        performCreateSecret(project, "secret-a", "value", "admin").andExpect(status().isOk());

        List<AuditOutboxEntity> outbox = mongoTemplate.find(query(where("projectId").is(project)), AuditOutboxEntity.class);
        assertThat(outbox).singleElement().satisfies(entry -> {
            assertThat(entry.getSyncStatus()).isEqualTo(AuditSyncStatus.PENDING);
            assertThat(entry.getAction()).isEqualTo("CREATE_SECRET");
        });

        relayer.tick();

        AuditOutboxEntity synced = mongoTemplate.findById(outbox.get(0).getId(), AuditOutboxEntity.class);
        assertThat(synced.getSyncStatus()).isEqualTo(AuditSyncStatus.SYNCED);
        assertThat(rowsOf(project)).singleElement().satisfies(row -> assertThat(row.getEventId()).isEqualTo(outbox.get(0).getId()));

        // re-sending the same entry (e.g. after a crash before it was marked SYNCED) is a no-op
        assertThat(store.insertMissing(List.of(outbox.get(0)))).isZero();
        assertThat(rowsOf(project)).hasSize(1);
    }

    @Test
    void readAudit_isStoredInMysql() throws Exception {
        String project = "mysql-read-" + UUID.randomUUID();
        performCreateSecret(project, "secret-r", "value", "admin").andExpect(status().isOk());
        performReadSecretValue(project, "secret-r", "admin").andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(rowsOf(project)).anySatisfy(row -> assertThat(row.getAction()).isEqualTo("READ_SECRET")));
    }

    @Test
    void derbyFlush_storesRowsWithCheckpoint_andSkipsResentRows() {
        String node = "it-node-" + UUID.randomUUID();
        String project = "mysql-flush-" + UUID.randomUUID();
        List<AuditEntry> batch = List.of(bufferedReadAudit(node, project, 1), bufferedReadAudit(node, project, 2));
        assertThat(flushStore.lastFlushedId(node)).isZero();

        flushStore.commitBatch(node, batch, 2);

        assertThat(flushStore.lastFlushedId(node)).isEqualTo(2);
        assertThat(flushStore.lastCheckpointUpdate(node)).isPresent();
        assertThat(rowsOf(project)).hasSize(2);

        // a crash after the commit but before the Derby rows were deleted re-sends the batch
        flushStore.commitBatch(node, batch, 2);
        assertThat(rowsOf(project)).hasSize(2);

        flushStore.deleteCheckpoint(node);
        assertThat(flushStore.lastFlushedId(node)).isZero();
    }

    @Test
    void cursorPaging_returnsEveryEntryOnceNewestFirst_evenWhenInsertedOutOfOrder() throws Exception {
        String project = "mysql-paging-" + UUID.randomUUID();
        Instant base = Instant.now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);
        List<AuditEntry> entries = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            entries.add(AuditEntry.builder().id("evt-" + project + "-" + i).projectId(project).resourceType("SECRET")
                    .resourceName("s").action("READ_SECRET").userId("admin")
                    .timestamp(base.plusSeconds(i / 2)) // pairs share a timestamp, exercising the id tie-breaker
                    .metadata(new HashMap<>()).build());
        }
        Collections.shuffle(entries, new Random(42));
        store.insertMissing(entries);

        List<Instant> seen = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        String cursor = null;
        do {
            var request = get("/v2/audit/projects/{projectId}", project).with(user("admin"))
                    .param("from", base.minusSeconds(1).toString()).param("limit", "50");
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            String body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<String> timestamps = JsonPath.read(body, "$.data.entries[*].timestamp");
            timestamps.forEach(t -> seen.add(Instant.parse(t)));
            ids.addAll(JsonPath.read(body, "$.data.entries[*].id"));
            cursor = JsonPath.read(body, "$.data.nextCursor");
        } while (cursor != null);

        assertThat(seen).hasSize(250);
        assertThat(ids).hasSize(250);
        assertThat(seen).isSortedAccordingTo(Comparator.reverseOrder());
    }

    /** A read audit as the Derby flush hands it over: the log id {@code node.rowId.timestamp} is its event id. */
    private static AuditEntry bufferedReadAudit(String node, String project, long derbyRowId) {
        Instant timestamp = Instant.now();
        Map<String, String> metadata = new HashMap<>();
        metadata.put(AuditEventIds.LOG_ID_KEY, node + "." + derbyRowId + "." + timestamp);
        return AuditEntry.builder().projectId(project).resourceType("SECRET").resourceName("s").action("READ_SECRET")
                .userId("admin").timestamp(timestamp).metadata(metadata).build();
    }

    private List<AuditEntryRow> rowsOf(String project) {
        return rowRepository.findAll().stream().filter(row -> project.equals(row.getProjectId())).toList();
    }
}
