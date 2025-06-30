package com.flipkart.grayskull.repositories;

import com.flipkart.grayskull.models.db.Project;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository interface for {@link Project} entities.
 */
@Repository
public interface ProjectRepository extends MongoRepository<Project, String> {

} 