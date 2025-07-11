package com.flipkart.grayskull.repositories;

import com.flipkart.grayskull.models.db.SecretData;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SecretDataRepository extends MongoRepository<SecretData, String> {

    Optional<SecretData> findBySecretIdAndDataVersion(String secretId, long dataVersion);

} 