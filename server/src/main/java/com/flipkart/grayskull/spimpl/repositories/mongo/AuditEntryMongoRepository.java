package com.flipkart.grayskull.spimpl.repositories.mongo;

import com.flipkart.grayskull.entities.AuditEntryEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * MongoDB repository interface for AuditEntryEntity.
 * Custom query methods are implemented in AuditEntryMongoRepositoryImpl.
 */
public interface AuditEntryMongoRepository extends MongoRepository<AuditEntryEntity, String> {

    /**
     * Finds audit entries by dynamic filters with pagination and sorting.
     *
     * @param projectId Optional project ID filter
     * @param resourceName Optional resource name filter
     * @param resourceType Optional resource type filter
     * @param action Optional action filter
     * @param offset Number of records to skip
     * @param limit Maximum number of records to return
     * @return List of matching audit entry entities
     */
    List<AuditEntryEntity> findByFilters(String projectId, String resourceName, String resourceType, String action, int offset, int limit);

    /**
     * Counts audit entries matching the given filters.
     *
     * @param projectId Optional project ID filter
     * @param resourceName Optional resource name filter
     * @param resourceType Optional resource type filter
     * @param action Optional action filter
     * @return Count of matching audit entries
     */
    long countByFilters(String projectId, String resourceName, String resourceType, String action);
}

