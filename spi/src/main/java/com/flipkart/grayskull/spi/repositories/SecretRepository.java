package com.flipkart.grayskull.spi.repositories;

import com.flipkart.grayskull.models.db.Secret;
import com.flipkart.grayskull.models.enums.LifecycleState;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.PagingAndSortingRepository;

import java.util.List;
import java.util.Optional;

/**
 * Generic data access interface for {@link Secret} metadata.
 * This interface is persistence-agnostic and defines the contract for secret metadata storage.
 */
public interface SecretRepository extends PagingAndSortingRepository<Secret, String> {

    /**
     * Saves a given secret entity. Use the returned instance for further operations.
     * @param entity the secret to save, must not be {@literal null}.
     * @return the saved secret; will never be {@literal null}.
     * @param <S> the type of the secret.
     */
    <S extends Secret> S save(S entity);

    /**
     * Finds a paginated list of secrets for a given project ID and state.
     *
     * @param projectId The ID of the project.
     * @param state     The state of the secrets to find.
     * @param pageable  The pagination information.
     * @return A list of secrets.
     */
    List<Secret> findByProjectIdAndState(String projectId, LifecycleState state, Pageable pageable);

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
     * @return An {@link Optional} containing the secret if found.
     */
    Optional<Secret> findByProjectIdAndName(String projectId, String name);

    /**
     * Finds a secret by its project ID, name, and state.
     *
     * @param projectId The ID of the project.
     * @param name      The name of the secret.
     * @param state     The state of the secret.
     * @return An {@link Optional} containing the secret if found.
     */
    Optional<Secret> findByProjectIdAndNameAndState(String projectId, String name, LifecycleState state);

}