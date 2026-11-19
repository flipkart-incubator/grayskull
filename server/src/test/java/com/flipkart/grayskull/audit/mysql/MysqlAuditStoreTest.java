package com.flipkart.grayskull.audit.mysql;

import com.flipkart.grayskull.audit.mysql.entity.AuditEntryRow;
import com.flipkart.grayskull.audit.mysql.repository.AuditEntryRowRepository;
import com.flipkart.grayskull.configuration.AuditQueryConfiguration;
import com.flipkart.grayskull.exception.BadRequestException;
import com.flipkart.grayskull.mappers.AuditEntryRowMapper;
import com.flipkart.grayskull.spi.models.AuditEntry;
import com.flipkart.grayskull.spi.models.AuditQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.QueryTimeoutException;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaQuery;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

class MysqlAuditStoreTest {

    private final AuditEntryRowRepository repository = mock();
    private final MysqlAuditStore store = new MysqlAuditStore(repository, new AuditQueryConfiguration(180, "svc:", "ldap:"),
            Mappers.getMapper(AuditEntryRowMapper.class));

    @Test
    @SuppressWarnings("unchecked")
    void insertMissing_skipsStoredAndRepeatedEvents() {
        AuditEntry stored = AuditEntry.builder().id("stored").projectId("p").userId("svc:app").build();
        AuditEntry fresh = AuditEntry.builder().id("fresh").projectId("p").userId("ldap:jane").build();
        AuditEntry freshAgain = AuditEntry.builder().id("fresh").projectId("p").userId("ldap:jane").build();
        when(repository.findExistingRowIds(anyCollection())).thenReturn(Set.of(AuditEventIds.rowId("stored")));

        int inserted = store.insertMissing(List.of(stored, fresh, freshAgain));

        assertThat(inserted).isEqualTo(1);
        ArgumentCaptor<List<AuditEntryRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(row -> {
            assertThat(row.getEventId()).isEqualTo("fresh");
            assertThat(row.getUserType()).isEqualTo("HUMAN");
            assertThat(row.isNew()).isTrue();
        });
    }

    @Test
    void insertMissing_emptyInput_touchesNothing() {
        assertThat(store.insertMissing(List.of())).isZero();
        verifyNoInteractions(repository);
    }

    @Test
    @SuppressWarnings("unchecked")
    void insertMissing_derivesRowIdFromLogId_andUserTypeFromPrefix() {
        AuditEntry readAudit = AuditEntry.builder().projectId("p").userId("svc:job").metadata(Map.of("logId", "n.1.t")).build();
        AuditEntry other = AuditEntry.builder().id("o").projectId("p").userId("other").build();
        AuditEntry anonymous = AuditEntry.builder().id("a").projectId("p").build();

        store.insertMissing(List.of(readAudit, other, anonymous));

        ArgumentCaptor<List<AuditEntryRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(3);
        assertThat(captor.getValue().get(2).getUserType()).isEqualTo("OTHER");
        AuditEntryRow row = captor.getValue().get(0);
        assertThat(row.getEventId()).isEqualTo("n.1.t");
        assertThat(row.getRowId()).isEqualTo(AuditEventIds.rowId("n.1.t"));
        assertThat(row.getUserType()).isEqualTo("SERVICE");
        assertThat(row.getMetadata()).containsEntry("logId", "n.1.t");
        assertThat(captor.getValue().get(1).getUserType()).isEqualTo("OTHER");
    }

    @Test
    @SuppressWarnings("unchecked")
    void findPage_queryTimeout_asksTheCallerToNarrowTheQuery() {
        EntityManager entityManager = mock(EntityManager.class, RETURNS_DEEP_STUBS);
        TypedQuery<AuditEntryRow> pageQuery = mock();
        when(entityManager.createQuery(any(CriteriaQuery.class))).thenReturn(pageQuery);
        when(pageQuery.setMaxResults(anyInt())).thenReturn(pageQuery);
        when(pageQuery.setHint(anyString(), any())).thenReturn(pageQuery);
        when(pageQuery.getResultList()).thenThrow(new QueryTimeoutException("statement cancelled"));
        ReflectionTestUtils.setField(store, "entityManager", entityManager);
        AuditQuery query = new AuditQuery("p", null, null, "READ_SECRET", null, null, null);

        assertThrows(BadRequestException.class, () -> store.findPage(query, null, 50));
    }

    @Test
    void findByFilters_rejectsDeepOffsets() {
        AuditQuery query = new AuditQuery("p", null, null, null, null, null, null);

        assertThrows(BadRequestException.class, () -> store.findByFilters(query, null, MysqlAuditStore.MAX_OFFSET + 1, 10));
    }
}
