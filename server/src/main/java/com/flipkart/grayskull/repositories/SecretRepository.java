package com.flipkart.grayskull.repositories;

import com.flipkart.grayskull.models.db.Secret;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SecretRepository extends MongoRepository<Secret, String> {

    List<Secret> findByProjectId(String projectId, Pageable pageable);

    long countByProjectId(String projectId);

    Optional<Secret> findByProjectIdAndName(String projectId, String name);

    void deleteByProjectIdAndName(String projectId, String name);
} 