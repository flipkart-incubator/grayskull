package com.flipkart.grayskull.audit.mysql;

import com.flipkart.grayskull.spi.models.AuditCursor;
import com.flipkart.grayskull.spi.models.AuditEntry;
import com.flipkart.grayskull.spi.models.AuditPage;
import com.flipkart.grayskull.spi.models.AuditQuery;
import com.flipkart.grayskull.spi.repositories.AuditEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * The audit repository: audits are stored in and served from MySQL.
 */
@Repository
@RequiredArgsConstructor
public class MysqlAuditEntryRepository implements AuditEntryRepository {

    private final MysqlAuditStore store;

    @Override
    public AuditEntry save(AuditEntry entry) {
        store.insertMissing(List.of(entry));
        return entry;
    }

    @Override
    public List<AuditEntry> saveAll(Iterable<AuditEntry> entries) {
        List<AuditEntry> list = new java.util.ArrayList<>();
        entries.forEach(list::add);
        store.insertMissing(list);
        return list;
    }

    @Override
    public List<AuditEntry> findByFilters(Optional<String> projectId, Optional<String> resourceName, Optional<String> resourceType, Optional<String> action, Optional<String> userType, Optional<Date> afterTimestamp, int offset, int limit) {
        return store.findByFilters(query(projectId, resourceName, resourceType, action, userType), afterTimestamp.map(Date::toInstant).orElse(null), offset, limit);
    }

    @Override
    public long countByFilters(Optional<String> projectId, Optional<String> resourceName, Optional<String> resourceType, Optional<String> action, Optional<String> userType, Optional<Date> afterTimestamp) {
        return store.countByFilters(query(projectId, resourceName, resourceType, action, userType), afterTimestamp.map(Date::toInstant).orElse(null));
    }

    @Override
    public AuditPage findPage(AuditQuery query, AuditCursor cursor, int limit) {
        return store.findPage(query, cursor, limit);
    }

    private static AuditQuery query(Optional<String> projectId, Optional<String> resourceName, Optional<String> resourceType, Optional<String> action, Optional<String> userType) {
        return new AuditQuery(projectId.orElse(null), resourceName.orElse(null), resourceType.orElse(null), action.orElse(null), userType.orElse(null), null, null);
    }
}
