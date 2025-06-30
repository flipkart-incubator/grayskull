package com.flipkart.grayskull.repositories;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import com.flipkart.grayskull.models.db.AuditEntry;

/**
 * Repository interface for {@link AuditEntry} entities.
 */
@Repository
public interface AuditEntryRepository extends MongoRepository<AuditEntry, String> {

} 