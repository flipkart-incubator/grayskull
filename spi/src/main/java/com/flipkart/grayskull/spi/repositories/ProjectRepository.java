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
     * Finds a project by its ID, or returns a transient project instance if not found.
     * <p>
     * This method is designed for authorization checks where a transient project
     * (not persisted to the database) is needed to evaluate permissions against
     * non-existent projects. This allows authorization rules (e.g., wildcard rules
     * for admins) to grant permission for creating resources in new projects.
     * 
     * @param id the project ID, must not be null.
     * @return a Project instance; either the persisted project or a transient one.
     */
    Project findByIdOrTransient(String id);

    /**
     * Saves a given project.
     * 
     * @param entity the project to save, must not be null.
     * @return the saved project; will never be null.
     */
    <S extends Project> S save(S entity);
}