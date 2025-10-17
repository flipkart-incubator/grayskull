package com.flipkart.grayskull.spimpl.repositories;

import com.flipkart.grayskull.entities.SecretDataEntity;
import com.flipkart.grayskull.spi.models.SecretData;
import com.flipkart.grayskull.spi.repositories.SecretDataRepository;
import com.flipkart.grayskull.spimpl.repositories.mongo.SecretDataMongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data MongoDB repository implementation for SecretData.
 * Implements the SPI contract using Spring Data.
 */
@Repository
public class SecretDataRepositoryImpl implements SecretDataRepository {

    private final SecretDataMongoRepository mongoRepository;

    public SecretDataRepositoryImpl(SecretDataMongoRepository mongoRepository) {
        this.mongoRepository = mongoRepository;
    }

    @Override
    public Optional<SecretData> getBySecretIdAndDataVersion(String secretId, long dataVersion) {
        return mongoRepository.findBySecretIdAndDataVersion(secretId, dataVersion).map(entity -> entity);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S extends SecretData> S save(S entity) {
        if (!(entity instanceof SecretDataEntity)) {
            throw new IllegalArgumentException(
                    "Expected SecretDataEntity but got: " + entity.getClass().getName());
        }
        return (S) mongoRepository.save((SecretDataEntity) entity);
    }
}
