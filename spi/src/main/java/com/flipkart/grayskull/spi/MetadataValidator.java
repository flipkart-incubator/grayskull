package com.flipkart.grayskull.spi;

import java.util.Map;

/**
 * Interface for doing the metadata validation during secret creation.
 * By-default a single validator is provided which checks for rotation and revocation urls for non-SELF providers.
 * More validators can be added by implementing this interface.
 * Implementations of this interface should throw a ResponseStatusException with HttpStatus.BAD_REQUEST if the metadata is invalid
 */
public interface MetadataValidator {
    /**
     * Validates the metadata of a secret.
     *
     * @param provider the secret provider
     * @param metadata the metadata of the secret
     * @throws ResponseStatusException with HttpStatus.BAD_REQUEST if the metadata is invalid
     */
    void validateMetadata(String provider, Map<String, Object> metadata);
}
