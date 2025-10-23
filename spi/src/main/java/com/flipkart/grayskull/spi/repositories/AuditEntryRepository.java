package com.flipkart.grayskull.spi.repositories;

import com.flipkart.grayskull.spi.models.AuditEntry;

import java.util.List;

/**
 * Generic data access interface for AuditEntry entities.
 * This interface defines the contract for persisting audit logs.
 */
public interface AuditEntryRepository {

    /**
     * Saves a given audit entry.
     * 
     * @param entity the audit entry to save, must not be null.
     * @return the saved audit entry; will never be null.
     */
    AuditEntry save(AuditEntry entity);

    /**
     * Saves all given audit entries.
     *
     * @param entities the audit entries to save, must not be null and must not contain null.
     * @return the saved audit entries; will never be null.
     */
    List<AuditEntry> saveAll(Iterable<AuditEntry> entities);
}