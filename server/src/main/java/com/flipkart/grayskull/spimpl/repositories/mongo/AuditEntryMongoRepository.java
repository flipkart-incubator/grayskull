package com.flipkart.grayskull.spimpl.repositories.mongo;

import com.flipkart.grayskull.entities.AuditEntryEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository interface for AuditEntryEntity.
 */
public interface AuditEntryMongoRepository extends MongoRepository<AuditEntryEntity, String> {
}

