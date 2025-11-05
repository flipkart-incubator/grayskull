package com.flipkart.grayskull.spimpl.repositories.mongo;

import com.flipkart.grayskull.entities.ProjectEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * MongoDB repository interface for ProjectEntity.
 */
public interface ProjectMongoRepository extends MongoRepository<ProjectEntity, String> {
}

