package com.flipkart.grayskull.spimpl.repositories.mongo;

import com.flipkart.grayskull.entities.SecretEntity;
import com.flipkart.grayskull.spi.models.enums.LifecycleState;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB repository interface for SecretEntity.
 */
public interface SecretMongoRepository extends MongoRepository<SecretEntity, String> {
    List<SecretEntity> findByProjectIdAndState(String projectId, LifecycleState state, Pageable pageable);

    long countByProjectIdAndState(String projectId, LifecycleState state);

    Optional<SecretEntity> findByProjectIdAndName(String projectId, String name);

    Optional<SecretEntity> findByProjectIdAndNameAndState(String projectId, String name, LifecycleState state);
}

