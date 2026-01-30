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
import org.springframework.stereotype.Repository;

import java.util.List;
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
    public void delete(Secret secret) {
        if (!(secret instanceof SecretEntity)) {
            throw new IllegalArgumentException(
                    "Expected SecretEntity but got: " + secret.getClass().getName());
        }
        mongoRepository.delete((SecretEntity) secret);
        secretDataMongoRepository.deleteAllBySecretId(secret.getId());
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
