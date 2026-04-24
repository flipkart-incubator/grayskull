package com.flipkart.grayskull.spimpl.repositories;

import com.flipkart.grayskull.entities.SecretEntity;
import com.flipkart.grayskull.spi.models.Secret;
import com.flipkart.grayskull.spi.models.enums.LifecycleState;
import com.flipkart.grayskull.spi.repositories.SecretRepository;
import com.flipkart.grayskull.spimpl.repositories.mongo.SecretDataMongoRepository;
import com.flipkart.grayskull.spimpl.repositories.mongo.SecretMongoRepository;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spring Data MongoDB repository implementation for Secret.
 * Implements the SPI contract using Spring Data.
 */
@Repository
@AllArgsConstructor
public class SecretRepositoryImpl implements SecretRepository {

    private final SecretMongoRepository mongoRepository;
    private final SecretDataMongoRepository secretDataMongoRepository;
    private final MongoTemplate mongoTemplate;

    @Override
    public List<Secret> findByProjectIdAndState(String projectId, LifecycleState state, int offset, int limit) {
        // For true offset-based pagination, always fetch from page 0 with size (offset + limit)
        // This ensures we get all records from start to our desired range
        // Note: PageRequest.of(page, size) calculates skip as page * size, so we must use page 0
        // to avoid incorrect offset calculations when size varies
        Pageable pageable = PageRequest.of(0, offset + limit);
        List<SecretEntity> entities = mongoRepository.findByProjectIdAndState(projectId, state, pageable);
        
        // Skip the offset records and limit to the requested amount
        return entities.stream()
                .skip(offset)
                .limit(limit)
                .map(Secret.class::cast)
                .toList();
    }

    @Override
    public long countByProjectIdAndState(String projectId, LifecycleState state) {
        return mongoRepository.countByProjectIdAndState(projectId, state);
    }

    @Override
    public Optional<Secret> findByProjectIdAndName(String projectId, String name) {
        return mongoRepository.findByProjectIdAndName(projectId, name).map(entity -> entity);
    }

    @Override
    public Optional<Secret> findByProjectIdAndNameAndState(String projectId, String name, LifecycleState state) {
        return mongoRepository.findByProjectIdAndNameAndState(projectId, name, state).map(entity -> entity);
    }

    @Override
    public List<Secret> findActiveByProjectAndNames(Map<String, List<String>> projectToNames) {
        Query query = buildActiveSecretsQuery(projectToNames);
        if (query == null) {
            return List.of();
        }
        return mongoTemplate.find(query, SecretEntity.class).stream()
                .map(Secret.class::cast)
                .toList();
    }

    /**
     * Builds {@code state = ACTIVE AND ((projectId = p1 AND name IN (...)) OR ...)} so the database
     * returns only requested (project, name) pairs
     *
     * @return {@code null} when there is nothing to query (empty map or only empty name lists).
     */
    private static Query buildActiveSecretsQuery(Map<String, List<String>> projectToNames) {
        if (projectToNames == null || projectToNames.isEmpty()) {
            return null;
        }

        List<Criteria> projectBranches = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : projectToNames.entrySet()) {
            String projectId = entry.getKey();
            List<String> names = entry.getValue();
            if (projectId == null || names == null || names.isEmpty()) {
                continue;
            }
            projectBranches.add(
                    new Criteria().andOperator(
                            Criteria.where("projectId").is(projectId),
                            Criteria.where("name").in(names)));
        }

        if (projectBranches.isEmpty()) {
            return null;
        }

        Criteria stateAndProjects = new Criteria().andOperator(
                Criteria.where("state").is(LifecycleState.ACTIVE),
                projectBranches.size() == 1
                        ? projectBranches.get(0)
                        : new Criteria().orOperator(projectBranches.toArray(Criteria[]::new)));

        return new Query(stateAndProjects);
    }

    @Override
    public void delete(Secret secret) {
        if (!(secret instanceof SecretEntity)) {
            throw new IllegalArgumentException(
                    "Expected SecretEntity but got: " + secret.getClass().getName());
        }
        secretDataMongoRepository.deleteAllBySecretId(secret.getId());
        mongoRepository.delete((SecretEntity) secret);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S extends Secret> S save(S entity) {
        if (!(entity instanceof SecretEntity)) {
            throw new IllegalArgumentException(
                    "Expected SecretEntity but got: " + entity.getClass().getName());
        }
        return (S) mongoRepository.save((SecretEntity) entity);
    }
}
