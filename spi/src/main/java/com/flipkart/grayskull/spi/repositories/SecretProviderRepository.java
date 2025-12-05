package com.flipkart.grayskull.spi.repositories;

import com.flipkart.grayskull.spi.models.SecretProvider;

import java.util.List;
import java.util.Optional;

public interface SecretProviderRepository {
    Optional<SecretProvider> findByName(String name);
    SecretProvider save(SecretProvider provider);
    List<SecretProvider> findAll();
}
