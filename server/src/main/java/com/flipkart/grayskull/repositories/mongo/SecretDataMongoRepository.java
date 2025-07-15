package com.flipkart.grayskull.repositories.mongo;

import com.flipkart.grayskull.models.db.SecretData;
import com.flipkart.grayskull.spi.repositories.SecretDataRepository;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * MongoDB-specific implementation of the {@link SecretDataRepository}.
 * Provides persistence for {@link SecretData} entities using Spring Data MongoDB.
 */
@Repository("secretDataMongoRepository")
public interface SecretDataMongoRepository extends MongoRepository<SecretData, String>, SecretDataRepository {
} 