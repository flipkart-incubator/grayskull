package com.flipkart.grayskull.spimpl.repositories;

import com.flipkart.grayskull.mappers.SecretProviderMapper;
import com.flipkart.grayskull.spi.models.SecretProvider;
import com.flipkart.grayskull.spi.repositories.SecretProviderRepository;
import com.flipkart.grayskull.spimpl.repositories.mongo.SecretProviderMongoRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@AllArgsConstructor
public class SecretProviderRepositoryImpl implements SecretProviderRepository {
    private final SecretProviderMongoRepository repository;
    private final SecretProviderMapper mapper;

    @Override
    public Optional<SecretProvider> findByName(String name) {
        return repository.findByName(name).map(SecretProvider.class::cast);
    }

    @Override
    public SecretProvider save(SecretProvider provider) {
        return repository.save(mapper.toEntity(provider)); // Entity extends SecretProvider, so this is valid
    }
    
    public List<SecretProvider> findAll() {
        return repository.findAll().stream()
                .map(SecretProvider.class::cast)
                .toList();
    }
}
