package com.flipkart.grayskull.spimpl.repositories.mongo;

import com.flipkart.grayskull.entities.SecretDataEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * MongoDB repository interface for SecretDataEntity.
 */
public interface SecretDataMongoRepository extends MongoRepository<SecretDataEntity, String> {
    Optional<SecretDataEntity> findBySecretIdAndDataVersion(String secretId, long dataVersion);
    void deleteAllBySecretId(String secretId);
}

