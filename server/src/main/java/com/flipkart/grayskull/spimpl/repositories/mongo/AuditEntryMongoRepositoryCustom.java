package com.flipkart.grayskull.spimpl.repositories.mongo;

import com.flipkart.grayskull.entities.AuditEntryEntity;

import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Custom repository fragment for AuditEntryEntity.
 * Contains custom query methods that are implemented in AuditEntryMongoRepositoryImpl.
 */
public interface AuditEntryMongoRepositoryCustom {

    /**
     * Finds audit entries by dynamic filters with pagination and sorting.
     *
     * @param projectId Optional project ID filter
     * @param resourceName Optional resource name filter
     * @param resourceType Optional resource type filter
     * @param action Optional action filter
     * @param userType Optional user type filter (e.g., "SERVICE", "HUMAN")
     * @param afterTimestamp Optional timestamp filter (entries after this time)
     * @param offset Number of records to skip
     * @param limit Maximum number of records to return
     * @return List of matching audit entry entities
     */
    List<AuditEntryEntity> findByFilters(Optional<String> projectId, Optional<String> resourceName, Optional<String> resourceType, Optional<String> action, Optional<String> userType, Optional<Date> afterTimestamp, int offset, int limit);

    /**
     * Counts audit entries matching the given filters.
     *
     * @param projectId Optional project ID filter
     * @param resourceName Optional resource name filter
     * @param resourceType Optional resource type filter
     * @param action Optional action filter
     * @param userType Optional user type filter (e.g., "SERVICE", "HUMAN")
     * @param afterTimestamp Optional timestamp filter (entries after this time)
     * @return Count of matching audit entries
     */
    long countByFilters(Optional<String> projectId, Optional<String> resourceName, Optional<String> resourceType, Optional<String> action, Optional<String> userType, Optional<Date> afterTimestamp);
}
