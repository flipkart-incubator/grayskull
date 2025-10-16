package com.flipkart.grayskull.spi.repositories;

import com.flipkart.grayskull.spi.models.SecretData;

import java.util.Optional;

/**
 * Generic data access interface for SecretData.
 * This interface defines the contract for storing and retrieving the actual,
 * versioned secret payloads.
 */
public interface SecretDataRepository {

    /**
     * Saves a given secret data.
     * 
     * @param entity the secret data to save, must not be null.
     * @return the saved secret data; will never be null.
     */
    <S extends SecretData> S save(S entity);

    /**
     * Gets a specific version of a secret's data.
     *
     * @param secretId    The ID of the parent Secret.
     * @param dataVersion The version of the data to retrieve.
     * @return An Optional containing the secret data if found.
     */
    Optional<SecretData> getBySecretIdAndDataVersion(String secretId, long dataVersion);
}