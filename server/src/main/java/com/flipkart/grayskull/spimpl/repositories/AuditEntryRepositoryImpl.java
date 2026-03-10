package com.flipkart.grayskull.spimpl.repositories;

import com.flipkart.grayskull.mappers.AuditEntryMapper;
import com.flipkart.grayskull.spi.models.AuditEntry;
import com.flipkart.grayskull.spi.repositories.AuditEntryRepository;
import com.flipkart.grayskull.spimpl.repositories.mongo.AuditEntryMongoRepository;
import lombok.AllArgsConstructor;
import org.springframework.data.util.Streamable;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data MongoDB repository implementation for AuditEntry.
 * Implements the SPI contract using Spring Data.
 */
@Repository
@AllArgsConstructor
public class AuditEntryRepositoryImpl implements AuditEntryRepository {

    private final AuditEntryMongoRepository mongoRepository;
    private final AuditEntryMapper auditEntryMapper;

    @Override
    public AuditEntry save(AuditEntry entry) {
        return mongoRepository.save(auditEntryMapper.toEntity(entry));
    }

    @Override
    public List<AuditEntry> saveAll(Iterable<AuditEntry> entries) {
        return mongoRepository.saveAll(Streamable.of(entries).map(auditEntryMapper::toEntity)).stream().map(AuditEntry.class::cast).toList();
    }

    @Override
    public List<AuditEntry> findByFilters(Optional<String> projectId, Optional<String> resourceName, Optional<String> resourceType, Optional<String> action, Optional<String> userType, Optional<Date> afterTimestamp, int offset, int limit) {

        return mongoRepository.findByFilters(projectId, resourceName, resourceType, action, userType, afterTimestamp, offset, limit)
                .stream()
                .map(AuditEntry.class::cast)
                .toList();
    }

    @Override
    public long countByFilters(Optional<String> projectId, Optional<String> resourceName, Optional<String> resourceType, Optional<String> action, Optional<String> userType, Optional<Date> afterTimestamp) {
        return mongoRepository.countByFilters(projectId, resourceName, resourceType, action, userType, afterTimestamp);
    }
}
