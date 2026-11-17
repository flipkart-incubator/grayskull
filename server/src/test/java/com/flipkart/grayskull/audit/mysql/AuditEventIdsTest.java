package com.flipkart.grayskull.audit.mysql;

import com.flipkart.grayskull.spi.models.AuditEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEventIdsTest {

    @Test
    void eventId_prefersDerbyLogId_thenEntryId() {
        AuditEntry read = AuditEntry.builder().id("ignored").metadata(Map.of("logId", "node.5.2026-10-01T00:00:00Z")).build();
        AuditEntry write = AuditEntry.builder().id("6ad07ae2b00167a9daf53c44").metadata(Map.of()).build();

        assertThat(AuditEventIds.eventId(read)).isEqualTo("node.5.2026-10-01T00:00:00Z");
        assertThat(AuditEventIds.eventId(write)).isEqualTo("6ad07ae2b00167a9daf53c44");
        assertThat(AuditEventIds.eventId(AuditEntry.builder().build())).isNotBlank();
    }

    @Test
    void rowId_isDeterministic() {
        assertThat(AuditEventIds.rowId("event-1")).isEqualTo(AuditEventIds.rowId("event-1"));
        assertThat(AuditEventIds.rowId("event-1")).isNotEqualTo(AuditEventIds.rowId("event-2"));
    }

    @Test
    void storedTimestamp_isTruncatedToMicros() {
        assertThat(AuditEventIds.toStoredTimestamp(Instant.parse("2026-10-15T07:03:32.553594615Z")))
                .isEqualTo(Instant.parse("2026-10-15T07:03:32.553594Z"));
    }
}
