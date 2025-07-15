package com.flipkart.grayskull.spi.repositories;

import com.flipkart.grayskull.models.db.SecretData;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

/**
 * Generic data access interface for {@link SecretData}.
 * This interface defines the contract for storing and retrieving the actual, versioned secret payloads.
 */
public interface SecretDataRepository extends CrudRepository<SecretData, String> {

  /**
   * Finds a specific version of a secret's data.
   *
   * @param secretId    The ID of the parent {@link com.flipkart.grayskull.models.db.Secret}.
   * @param dataVersion The version of the data to retrieve.
   * @return An {@link Optional} containing the secret data if found.
   */
  Optional<SecretData> findBySecretIdAndDataVersion(String secretId, long dataVersion);

} 