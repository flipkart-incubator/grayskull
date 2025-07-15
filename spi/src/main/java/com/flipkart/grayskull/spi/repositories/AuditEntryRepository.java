package com.flipkart.grayskull.spi.repositories;

import com.flipkart.grayskull.models.db.AuditEntry;
import org.springframework.data.repository.CrudRepository;

/**
 * Generic data access interface for {@link AuditEntry} entities.
 * This interface defines the contract for persisting audit logs.
 */
public interface AuditEntryRepository extends CrudRepository<AuditEntry, String> {

} 