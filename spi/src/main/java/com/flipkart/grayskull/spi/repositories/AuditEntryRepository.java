package com.flipkart.grayskull.spi.repositories;

import com.flipkart.grayskull.spi.models.AuditEntry;

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
    <S extends AuditEntry> S save(S entity);
}