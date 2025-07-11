package com.flipkart.grayskull.repositories;

import com.flipkart.grayskull.models.db.Secret;
import com.flipkart.grayskull.models.enums.SecretState;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SecretRepository extends MongoRepository<Secret, String> {

    List<Secret> findByProjectIdAndState(String projectId, SecretState state, Pageable pageable);

    long countByProjectIdAndState(String projectId, SecretState state);

    Optional<Secret> findByProjectIdAndName(String projectId, String name);

    Optional<Secret> findByProjectIdAndNameAndState(String projectId, String name, SecretState state);

} 