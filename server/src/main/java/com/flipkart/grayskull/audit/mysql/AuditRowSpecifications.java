package com.flipkart.grayskull.audit.mysql;

import com.flipkart.grayskull.audit.mysql.entity.AuditEntryRow;
import com.flipkart.grayskull.spi.models.AuditCursor;
import com.flipkart.grayskull.spi.models.AuditQuery;
import lombok.experimental.UtilityClass;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Query building blocks for {@link AuditEntryRow}. Only filters that are present become predicates, so MySQL can use
 * the index matching the filters given (every index ends in {@code ts, id}).
 */
@UtilityClass
public class AuditRowSpecifications {

    public static final String TS = "ts";
    public static final String ROW_ID = "rowId";

    private static final Set<String> KNOWN_USER_TYPES = Set.of("SERVICE", "HUMAN");

    public static Specification<AuditEntryRow> matching(AuditQuery query) {
        String userType = query.userType() != null && KNOWN_USER_TYPES.contains(query.userType())
                ? query.userType()
                : null;

        return Specification.allOf(
                equalIfPresent("projectId", query.projectId()),
                equalIfPresent("resourceName", query.resourceName()),
                equalIfPresent("resourceType", query.resourceType()),
                equalIfPresent("action", query.action()),
                equalIfPresent("userType", userType),
                query.from() == null ? null : (root, q, cb) -> cb.greaterThanOrEqualTo(root.get(TS), query.from()),
                query.to() == null ? null : (root, q, cb) -> cb.lessThan(root.get(TS), query.to())
        );
    }

    private static Specification<AuditEntryRow> equalIfPresent(String field, Object value) {
        return value == null ? null : (root, q, cb) -> cb.equal(root.get(field), value);
    }

    /** Entries strictly after {@code timestamp} in the (afterTimestamp) filter of the offset API. */
    public static Specification<AuditEntryRow> after(Instant timestamp) {
        return (root, q, cb) -> cb.greaterThan(root.get(TS), timestamp);
    }

    /** Keyset condition: entries that come after the cursor in newest-first ({@code ts desc, rowId desc}) order. */
    public static Specification<AuditEntryRow> afterCursor(AuditCursor cursor) {
        Instant ts = cursor.timestamp();
        UUID rowId = UUID.fromString(cursor.id());
        return (root, q, cb) -> cb.or(
                cb.lessThan(root.get(TS), ts),
                cb.and(cb.equal(root.get(TS), ts), cb.lessThan(root.get(ROW_ID), rowId)));
    }
}
