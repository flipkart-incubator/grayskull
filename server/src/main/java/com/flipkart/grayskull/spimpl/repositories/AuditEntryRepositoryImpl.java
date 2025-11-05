package com.flipkart.grayskull.spimpl.repositories;

import com.flipkart.grayskull.entities.AuditEntryEntity;
import com.flipkart.grayskull.spi.models.AuditEntry;
import com.flipkart.grayskull.spi.repositories.AuditEntryRepository;
import com.flipkart.grayskull.spimpl.repositories.mongo.AuditEntryMongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data MongoDB repository implementation for AuditEntry.
 * Implements the SPI contract using Spring Data.
 */
@Repository
public class AuditEntryRepositoryImpl implements AuditEntryRepository {

    private final AuditEntryMongoRepository mongoRepository;

    public AuditEntryRepositoryImpl(AuditEntryMongoRepository mongoRepository) {
        this.mongoRepository = mongoRepository;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S extends AuditEntry> S save(S entity) {
        if (!(entity instanceof AuditEntryEntity)) {
            throw new IllegalArgumentException(
                    "Expected AuditEntryEntity but got: " + entity.getClass().getName());
        }
        return (S) mongoRepository.save((AuditEntryEntity) entity);
    }
}
