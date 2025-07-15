package com.flipkart.grayskull.spi.repositories;

import com.flipkart.grayskull.models.db.Project;
import org.springframework.data.repository.CrudRepository;

/**
 * Generic data access interface for {@link Project} entities.
 * This interface defines the contract for managing projects in a persistence-agnostic way.
 */
public interface ProjectRepository extends CrudRepository<Project, String> {

} 