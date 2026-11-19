package com.flipkart.grayskull.service.impl;

import com.flipkart.grayskull.audit.AuditCursorCodec;
import com.flipkart.grayskull.models.dto.response.AuditPageResponse;
import com.flipkart.grayskull.spi.models.AuditCursor;
import com.flipkart.grayskull.spi.models.AuditEntry;
import com.flipkart.grayskull.spi.models.AuditPage;
import com.flipkart.grayskull.spi.models.AuditQuery;
import com.flipkart.grayskull.spi.repositories.AuditEntryRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AuditServiceImplTest {

    private final AuditEntryRepository repository = mock();
    private final AuditServiceImpl service = new AuditServiceImpl(repository);

    @Test
    void getAuditPage_passesQueryThrough_andEncodesCursors() {
        AuditCursor previous = new AuditCursor(Instant.parse("2026-10-19T00:00:00Z"), "prev-id");
        AuditCursor next = new AuditCursor(Instant.parse("2026-10-18T00:00:00Z"), "next-id");
        AuditEntry entry = AuditEntry.builder().projectId("p").build();
        AuditQuery query = new AuditQuery("p", "s", null, null, null, null, Instant.parse("2026-10-21T00:00:00Z"));
        when(repository.findPage(query, previous, 100)).thenReturn(new AuditPage(List.of(entry), next, true));

        AuditPageResponse response = service.getAuditPage(query, AuditCursorCodec.encode(previous), 100);

        assertThat(response.getEntries()).containsExactly(entry);
        assertThat(response.isHasMore()).isTrue();
        assertThat(AuditCursorCodec.decode(response.getNextCursor())).isEqualTo(next);
    }

    @Test
    void getAuditPage_lastPageHasNoCursor() {
        AuditQuery query = new AuditQuery(null, null, null, null, null, null, null);
        when(repository.findPage(eq(query), eq(null), eq(10))).thenReturn(new AuditPage(List.of(), null, false));

        AuditPageResponse response = service.getAuditPage(query, null, 10);

        assertThat(response.getNextCursor()).isNull();
        assertThat(response.isHasMore()).isFalse();
    }
}
