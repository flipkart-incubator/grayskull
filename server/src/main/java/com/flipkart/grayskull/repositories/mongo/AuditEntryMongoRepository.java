package com.flipkart.grayskull.repositories.mongo;

import com.flipkart.grayskull.models.db.AuditEntry;
import com.flipkart.grayskull.spi.repositories.AuditEntryRepository;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * MongoDB-specific implementation of the {@link AuditEntryRepository}.
 * Provides persistence for {@link AuditEntry} entities using Spring Data MongoDB.
 */
@Repository("auditEntryMongoRepository")
public interface AuditEntryMongoRepository extends MongoRepository<AuditEntry, String>, AuditEntryRepository {
} 