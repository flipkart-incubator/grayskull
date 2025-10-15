package com.flipkart.grayskull.spi.repositories;

import com.flipkart.grayskull.spi.models.Project;

import java.util.Optional;

/**
 * Generic data access interface for Project entities.
 * This interface defines the contract for managing projects in a
 * persistence-agnostic way.
 */
public interface ProjectRepository {

    /**
     * Finds a project by its ID.
     * 
     * @param id the project ID, must not be null.
     * @return an Optional containing the project if found.
     */
    Optional<Project> findById(String id);

    /**
     * Saves a given project.
     * 
     * @param entity the project to save, must not be null.
     * @return the saved project; will never be null.
     */
    <S extends Project> S save(S entity);
}