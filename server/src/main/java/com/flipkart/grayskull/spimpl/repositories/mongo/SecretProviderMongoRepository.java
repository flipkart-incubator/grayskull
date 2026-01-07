package com.flipkart.grayskull.spimpl.repositories.mongo;

import com.flipkart.grayskull.entities.SecretProviderEntity;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface SecretProviderMongoRepository extends CrudRepository<SecretProviderEntity, String> {
    Optional<SecretProviderEntity> findByName(String name);
    List<SecretProviderEntity> findAll();
}
