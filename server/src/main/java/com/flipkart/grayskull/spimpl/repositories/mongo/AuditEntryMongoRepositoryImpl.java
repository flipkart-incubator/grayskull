package com.flipkart.grayskull.spimpl.repositories.mongo;

import com.flipkart.grayskull.entities.AuditEntryEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB-specific implementation of custom AuditEntry queries.
 * Uses MongoTemplate for dynamic query building.
 * 
 * Note: Spring Data automatically detects this as the implementation for
 * AuditEntryMongoRepository's custom methods based on naming convention (*Impl).
 */
@RequiredArgsConstructor
public class AuditEntryMongoRepositoryImpl {

    private final MongoTemplate mongoTemplate;

    public List<AuditEntryEntity> findByFilters(Optional<String> projectId, Optional<String> resourceName, Optional<String> resourceType, Optional<String> action, int offset, int limit) {

        Query query = buildFilterQuery(projectId, resourceName, resourceType, action);
        query.with(Sort.by(Sort.Direction.DESC, "timestamp"));
        query.skip(offset);
        query.limit(limit);

        return mongoTemplate.find(query, AuditEntryEntity.class);
    }

    public long countByFilters(Optional<String> projectId, Optional<String> resourceName, Optional<String> resourceType, Optional<String> action) {

        Query query = buildFilterQuery(projectId, resourceName, resourceType, action);
        return mongoTemplate.count(query, AuditEntryEntity.class);
    }

    /**
     * Builds a MongoDB query with dynamic filters.
     * Only adds criteria for present Optional parameters.
     *
     * @param projectId Optional project ID
     * @param resourceName Optional resource name
     * @param resourceType Optional resource type
     * @param action Optional action
     * @return MongoDB Query object with appropriate filters
     */
    private Query buildFilterQuery(Optional<String> projectId, Optional<String> resourceName, Optional<String> resourceType, Optional<String> action) {

        Query query = new Query();

        projectId.ifPresent(id -> query.addCriteria(Criteria.where("projectId").is(id)));
        resourceName.ifPresent(name -> query.addCriteria(Criteria.where("resourceName").is(name)));
        resourceType.ifPresent(type -> query.addCriteria(Criteria.where("resourceType").is(type)));
        action.ifPresent(act -> query.addCriteria(Criteria.where("action").is(act)));

        return query;
    }
}


