package com.flipkart.grayskull.spi.repositories;

import com.flipkart.grayskull.spi.models.Secret;
import com.flipkart.grayskull.spi.models.enums.LifecycleState;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generic data access interface for Secret metadata.
 * This interface is persistence-agnostic and defines the contract for secret
 * metadata storage.
 */
public interface SecretRepository {

    /**
     * Saves a given secret entity. Use the returned instance for further
     * operations.
     * 
     * @param entity the secret to save, must not be null.
     * @return the saved secret; will never be null.
     * @param <S> the type of the secret.
     */
    <S extends Secret> S save(S entity);

    /**
     * Finds a paginated list of secrets for a given project ID and state.
     *
     * @param projectId The ID of the project.
     * @param state     The state of the secrets to find.
     * @param offset    The starting offset for pagination.
     * @param limit     The maximum number of secrets to return.
     * @return A list of secrets.
     */
    List<Secret> findByProjectIdAndState(String projectId, LifecycleState state, int offset, int limit);

    /**
     * Counts the total number of secrets for a given project ID and state.
     *
     * @param projectId The ID of the project.
     * @param state     The state of the secrets to count.
     * @return The total number of secrets.
     */
    long countByProjectIdAndState(String projectId, LifecycleState state);

    /**
     * Finds a secret by its project ID and name.
     *
     * @param projectId The ID of the project.
     * @param name      The name of the secret.
     * @return An Optional containing the secret if found.
     */
    Optional<Secret> findByProjectIdAndName(String projectId, String name);

    /**
     * Finds a secret by its project ID, name, and state.
     *
     * @param projectId The ID of the project.
     * @param name      The name of the secret.
     * @param state     The state of the secret.
     * @return An Optional containing the secret if found.
     */
    Optional<Secret> findByProjectIdAndNameAndState(String projectId, String name, LifecycleState state);

    /**
     * Finds all active secrets matching the given (projectId, name) combinations
     * in a single cross-project operation.
     *
     * @param projectToNames Map of projectId to list of secret names to find.
     * @return A list of matching active secrets.
     */
    List<Secret> findActiveByProjectAndNames(Map<String, List<String>> projectToNames);

    /**
     * Deletes a secret.
     *
     * @param secret The secret to delete.
     */
    void delete(Secret secret);
}