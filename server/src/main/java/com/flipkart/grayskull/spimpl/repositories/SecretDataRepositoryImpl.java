package com.flipkart.grayskull.spimpl.repositories;

import com.flipkart.grayskull.entities.SecretDataEntity;
import com.flipkart.grayskull.spi.models.SecretData;
import com.flipkart.grayskull.spi.repositories.SecretDataRepository;
import com.flipkart.grayskull.spimpl.repositories.mongo.SecretDataMongoRepository;
import lombok.AllArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spring Data MongoDB repository implementation for SecretData.
 * Implements the SPI contract using Spring Data.
 */
@Repository
@AllArgsConstructor
public class SecretDataRepositoryImpl implements SecretDataRepository {

    private final SecretDataMongoRepository mongoRepository;
    private final MongoTemplate mongoTemplate;

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

    @Override
    public List<SecretData> findBySecretIdAndVersionPairs(Map<String, Long> secretIdToVersion) {
        if (secretIdToVersion.isEmpty()) {
            return List.of();
        }
        List<Criteria> orCriteria = new ArrayList<>();
        for (Map.Entry<String, Long> entry : secretIdToVersion.entrySet()) {
            orCriteria.add(Criteria.where("secretId").is(entry.getKey())
                    .and("dataVersion").is(entry.getValue()));
        }
        Query query = new Query(new Criteria().orOperator(orCriteria));
        return mongoTemplate.find(query, SecretDataEntity.class).stream()
                .map(SecretData.class::cast)
                .toList();
    }
}
