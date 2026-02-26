package com.flipkart.grayskull.spi.repositories;

import com.flipkart.grayskull.spi.models.AuditEntry;

import java.util.List;
import java.util.Optional;

/**
 * Generic data access interface for AuditEntry entities.
 * This interface defines the contract for persisting and querying audit logs.
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

    /**
     * Finds audit entries by optional filters with pagination.
     * All filter parameters are optional.
     * 
     * @param projectId Optional project ID filter
     * @param resourceName Optional resource name filter
     * @param resourceType Optional resource type filter
     * @param action Optional action filter
     * @param offset Pagination offset
     * @param limit Pagination limit
     * @return List of audit entries matching the filters
     */
    List<AuditEntry> findByFilters(Optional<String> projectId, Optional<String> resourceName, Optional<String> resourceType, Optional<String> action, int offset, int limit);

    /**
     * Counts audit entries matching the optional filters.
     * All filter parameters are optional.
     * 
     * @param projectId Optional project ID filter
     * @param resourceName Optional resource name filter
     * @param resourceType Optional resource type filter
     * @param action Optional action filter
     * @return Count of audit entries matching the filters
     */
    long countByFilters(Optional<String> projectId, Optional<String> resourceName, Optional<String> resourceType, Optional<String> action);
}