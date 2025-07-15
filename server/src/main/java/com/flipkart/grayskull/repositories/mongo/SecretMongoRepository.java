package com.flipkart.grayskull.repositories.mongo;

import com.flipkart.grayskull.models.db.Secret;
import com.flipkart.grayskull.spi.repositories.SecretRepository;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * MongoDB-specific implementation of the {@link SecretRepository}.
 * This interface leverages Spring Data MongoDB to provide persistence for {@link Secret} entities.
 */
@Repository("secretMongoRepository")
public interface SecretMongoRepository extends MongoRepository<Secret, String>, SecretRepository {
} 