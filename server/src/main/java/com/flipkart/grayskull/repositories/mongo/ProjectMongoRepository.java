package com.flipkart.grayskull.repositories.mongo;

import com.flipkart.grayskull.models.db.Project;
import com.flipkart.grayskull.spi.repositories.ProjectRepository;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * MongoDB-specific implementation of the {@link ProjectRepository}.
 * Provides persistence for {@link Project} entities using Spring Data MongoDB.
 */
@Repository("projectMongoRepository")
public interface ProjectMongoRepository extends MongoRepository<Project, String>, ProjectRepository {
} 