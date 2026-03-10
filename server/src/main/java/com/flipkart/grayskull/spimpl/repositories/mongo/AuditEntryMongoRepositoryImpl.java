package com.flipkart.grayskull.spimpl.repositories.mongo;

import com.flipkart.grayskull.configuration.UserTypeConfiguration;
import com.flipkart.grayskull.entities.AuditEntryEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.Date;
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
public class AuditEntryMongoRepositoryImpl implements AuditEntryMongoRepositoryCustom {

    private final MongoTemplate mongoTemplate;
    private final UserTypeConfiguration userTypeConfig;

    public List<AuditEntryEntity> findByFilters(Optional<String> projectId, Optional<String> resourceName, Optional<String> resourceType, Optional<String> action, Optional<String> userType, Optional<Date> afterTimestamp, int offset, int limit) {

        Query query = buildFilterQuery(projectId, resourceName, resourceType, action, userType, afterTimestamp);
        query.with(Sort.by(Sort.Direction.DESC, "timestamp"));
        query.skip(offset);
        query.limit(limit);

        return mongoTemplate.find(query, AuditEntryEntity.class);
    }

    public long countByFilters(Optional<String> projectId, Optional<String> resourceName, Optional<String> resourceType, Optional<String> action, Optional<String> userType, Optional<Date> afterTimestamp) {

        Query query = buildFilterQuery(projectId, resourceName, resourceType, action, userType, afterTimestamp);
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
     * @param userType Optional user type string (e.g., "SERVICE", "HUMAN")
     * @param afterTimestamp Optional timestamp filter (entries after this time)
     * @return MongoDB Query object with appropriate filters
     */
    private Query buildFilterQuery(Optional<String> projectId, Optional<String> resourceName, Optional<String> resourceType, Optional<String> action, Optional<String> userType, Optional<Date> afterTimestamp) {

        Query query = new Query();

        projectId.ifPresent(id -> query.addCriteria(Criteria.where("projectId").is(id)));
        resourceName.ifPresent(name -> query.addCriteria(Criteria.where("resourceName").is(name)));
        resourceType.ifPresent(type -> query.addCriteria(Criteria.where("resourceType").is(type)));
        action.ifPresent(act -> query.addCriteria(Criteria.where("action").is(act)));
        
        userType.ifPresent(typeString -> {
            String prefix = getUserTypePrefix(typeString);
            if (prefix != null) {
                query.addCriteria(Criteria.where("userId").regex("^" + prefix));
            }
        });
        
        // Add timestamp filter
        afterTimestamp.ifPresent(date -> query.addCriteria(Criteria.where("timestamp").gt(date)));

        return query;
    }
    
    /**
     * Gets the userId prefix based on the user type string.
     *
     * @param userType The user type string (e.g., "SERVICE", "HUMAN")
     * @return The prefix pattern for the given user type, or null if unknown
     */
    private String getUserTypePrefix(String userType) {
        return switch (userType) {
            case "SERVICE" -> userTypeConfig.getServiceUserPrefix();
            case "HUMAN" -> userTypeConfig.getHumanUserPrefix();
            default -> null;
        };
    }
}