package com.flipkart.grayskull.audit.mysql;

import com.flipkart.grayskull.audit.mysql.entity.AuditEntryRow;
import com.flipkart.grayskull.audit.mysql.repository.AuditEntryRowRepository;
import com.flipkart.grayskull.configuration.AuditQueryConfiguration;
import com.flipkart.grayskull.exception.BadRequestException;
import com.flipkart.grayskull.mappers.AuditEntryRowMapper;
import com.flipkart.grayskull.spi.models.AuditCursor;
import com.flipkart.grayskull.spi.models.AuditEntry;
import com.flipkart.grayskull.spi.models.AuditPage;
import com.flipkart.grayskull.spi.models.AuditQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.QueryTimeoutException;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

import static com.flipkart.grayskull.audit.mysql.AuditRowSpecifications.*;

/**
 * Reads and writes audit rows in MySQL. Inserts are idempotent: rows whose id is already stored are skipped,
 * so re-sending a batch after a failure never creates duplicates.
 * Reads are single SELECTs and run without a transaction, which saves the begin, commit and read-only round trips.
 */
@Service
public class MysqlAuditStore {

    /** Deepest offset the legacy offset API serves; deeper pages must use cursor paging. */
    static final int MAX_OFFSET = 10_000;

    private static final String QUERY_TIMEOUT_HINT = "jakarta.persistence.query.timeout";

    /**
     * Longest a cursor-API page query may run. Pages are index range scans, so this only stops filter combinations
     * that match almost nothing in a large index range; the caller is asked to narrow the query instead.
     */
    private static final int PAGE_QUERY_TIMEOUT_MS = 5_000;

    private final AuditEntryRowRepository repository;
    private final AuditQueryConfiguration auditConfig;
    private final AuditEntryRowMapper rowMapper;

    @PersistenceContext(unitName = MysqlAuditConfiguration.PERSISTENCE_UNIT)
    private EntityManager entityManager;

    public MysqlAuditStore(AuditEntryRowRepository repository, AuditQueryConfiguration auditConfig, AuditEntryRowMapper rowMapper) {
        this.repository = repository;
        this.auditConfig = auditConfig;
        this.rowMapper = rowMapper;
    }

    /**
     * Inserts the entries that are not stored yet, in one transaction (batched by Hibernate).
     *
     * @return number of rows inserted
     */
    @Transactional(transactionManager = MysqlAuditConfiguration.TRANSACTION_MANAGER)
    public int insertMissing(Collection<? extends AuditEntry> entries) {
        Map<UUID, AuditEntryRow> rows = new LinkedHashMap<>();
        for (AuditEntry entry : entries) {
            AuditEntryRow row = rowMapper.toRow(entry, AuditEventIds.eventId(entry), userType(entry.getUserId()));
            rows.putIfAbsent(row.getRowId(), row);
        }
        if (rows.isEmpty()) {
            return 0;
        }

        Set<UUID> existing = repository.findExistingRowIds(rows.keySet());
        List<AuditEntryRow> missing = rows.values().stream().filter(row -> !existing.contains(row.getRowId())).toList();
        repository.saveAll(missing);
        return missing.size();
    }

    /**
     * One page, newest first ({@code ts desc, rowId desc}; the row id breaks timestamp ties), starting after {@code cursor}.
     *
     * @throws BadRequestException if the query runs longer than {@link #PAGE_QUERY_TIMEOUT_MS}
     */
    public AuditPage findPage(AuditQuery query, AuditCursor cursor, int limit) {
        Specification<AuditEntryRow> spec = matching(query);
        if (cursor != null) {
            spec = spec.and(afterCursor(cursor));
        }
        List<AuditEntryRow> rows;
        try {
            rows = newestFirst(spec).setMaxResults(limit + 1)
                    .setHint(QUERY_TIMEOUT_HINT, PAGE_QUERY_TIMEOUT_MS)
                    .getResultList();
        } catch (QueryTimeoutException e) {
            // not IllegalArgumentException: the @Repository adapter would translate that into a 500
            throw new BadRequestException("Audit query took too long; narrow it with from/to or a more specific filter.");
        }
        boolean hasMore = rows.size() > limit;
        List<AuditEntryRow> pageRows = hasMore ? rows.subList(0, limit) : rows;
        AuditCursor nextCursor = null;
        if (hasMore) {
            AuditEntryRow last = pageRows.get(pageRows.size() - 1);
            nextCursor = new AuditCursor(last.getTs(), last.getRowId().toString());
        }
        return new AuditPage(pageRows.stream().map(rowMapper::toEntry).toList(), nextCursor, hasMore);
    }

    /** Offset paging for the v1 API, capped at {@link #MAX_OFFSET}. */
    public List<AuditEntry> findByFilters(AuditQuery query, Instant afterTimestamp, int offset, int limit) {
        if (offset > MAX_OFFSET) {
            throw new BadRequestException("Offset paging is limited to " + MAX_OFFSET + " entries; use the /v2/audit cursor API for deeper pages.");
        }
        return newestFirst(withAfter(query, afterTimestamp)).setFirstResult(offset).setMaxResults(limit)
                .getResultList().stream().map(rowMapper::toEntry).toList();
    }

    public long countByFilters(AuditQuery query, Instant afterTimestamp) {
        return repository.count(withAfter(query, afterTimestamp));
    }

    private TypedQuery<AuditEntryRow> newestFirst(Specification<AuditEntryRow> spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<AuditEntryRow> criteriaQuery = cb.createQuery(AuditEntryRow.class);
        Root<AuditEntryRow> root = criteriaQuery.from(AuditEntryRow.class);
        Predicate predicate = spec.toPredicate(root, criteriaQuery, cb);
        // null when no filter is given (Hibernate rejects where(null))
        if (predicate != null) {
            criteriaQuery.where(predicate);
        }
        criteriaQuery.orderBy(cb.desc(root.get(TS)), cb.desc(root.get(ROW_ID)));
        return entityManager.createQuery(criteriaQuery);
    }

    private static Specification<AuditEntryRow> withAfter(AuditQuery query, Instant afterTimestamp) {
        Specification<AuditEntryRow> spec = matching(query);
        return afterTimestamp == null ? spec : spec.and(after(afterTimestamp));
    }

    private String userType(String userId) {
        if (userId != null && userId.startsWith(auditConfig.getServiceUserPrefix())) {
            return "SERVICE";
        }
        if (userId != null && userId.startsWith(auditConfig.getHumanUserPrefix())) {
            return "HUMAN";
        }
        return "OTHER";
    }
}
