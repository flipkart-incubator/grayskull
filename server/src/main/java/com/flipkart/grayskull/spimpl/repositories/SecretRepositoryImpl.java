package com.flipkart.grayskull.spimpl.repositories;

import com.flipkart.grayskull.entities.SecretEntity;
import com.flipkart.grayskull.spi.models.Secret;
import com.flipkart.grayskull.spi.models.enums.LifecycleState;
import com.flipkart.grayskull.spi.repositories.SecretRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Internal MongoDB repository for SecretEntity.
 */
interface SecretMongoRepository extends MongoRepository<SecretEntity, String> {
    List<SecretEntity> findByProjectIdAndState(String projectId, LifecycleState state, Pageable pageable);

    long countByProjectIdAndState(String projectId, LifecycleState state);

    Optional<SecretEntity> findByProjectIdAndName(String projectId, String name);

    Optional<SecretEntity> findByProjectIdAndNameAndState(String projectId, String name, LifecycleState state);
}

/**
 * Spring Data MongoDB repository implementation for Secret.
 * Implements the SPI contract using Spring Data.
 */
@Repository
public class SecretRepositoryImpl implements SecretRepository {

    private final SecretMongoRepository mongoRepository;

    public SecretRepositoryImpl(SecretMongoRepository mongoRepository) {
        this.mongoRepository = mongoRepository;
    }

    @Override
    public List<Secret> findByProjectIdAndState(String projectId, LifecycleState state, int offset, int limit) {
        return mongoRepository.findByProjectIdAndState(projectId, state, PageRequest.of(offset, limit))
                .stream()
                .map(entity -> (Secret) entity)
                .collect(Collectors.toList());
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
    @SuppressWarnings("unchecked")
    public <S extends Secret> S save(S entity) {
        if (!(entity instanceof SecretEntity)) {
            throw new IllegalArgumentException(
                    "Expected SecretEntity but got: " + entity.getClass().getName());
        }
        return (S) mongoRepository.save((SecretEntity) entity);
    }
}
