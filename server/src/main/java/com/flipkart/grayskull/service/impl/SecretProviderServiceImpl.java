package com.flipkart.grayskull.service.impl;

import com.flipkart.grayskull.configuration.KmsConfig;
import com.flipkart.grayskull.exception.AlreadyExistsException;
import com.flipkart.grayskull.exception.NotFoundException;
import com.flipkart.grayskull.mappers.SecretProviderMapper;
import com.flipkart.grayskull.models.dto.request.CreateSecretProviderRequest;
import com.flipkart.grayskull.models.dto.request.SecretProviderRequest;
import com.flipkart.grayskull.service.interfaces.SecretProviderService;
import com.flipkart.grayskull.service.utils.SecretEncryptionUtil;
import com.flipkart.grayskull.spi.models.SecretProvider;
import com.flipkart.grayskull.spi.repositories.SecretProviderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SecretProviderServiceImpl implements SecretProviderService {

    private final SecretProviderRepository secretProviderRepository;
    private final SecretProviderMapper secretProviderMapper;
    private final SecretEncryptionUtil secretEncryptionUtil;
    private final KmsConfig kmsConfig;

    /**
     * Lists all secret providers.
     * This page is not paginated because we don't expect a large number of secret providers.
     *
     * @return A list of {@link SecretProvider} containing all providers.
     */
    @Override
    public List<SecretProvider> listProviders() {
        return secretProviderRepository.findAll();
    }

    @Override
    public SecretProvider getProvider(String name) {
        return secretProviderRepository.findByName(name)
                .orElseThrow(() -> new NotFoundException("Secret provider not found with name: " + name));
    }

    @Override
    @Transactional
    public SecretProvider createProvider(CreateSecretProviderRequest request) {
        log.debug("Creating secret provider with name: {}", request.getName());
        
        // Create new provider entity
        SecretProvider provider = secretProviderMapper.requestToSecretProvider(request);
        secretEncryptionUtil.encrypt(provider.getAuthAttributes(), kmsConfig.getDefaultKeyId());

        try {
            SecretProvider savedProvider = secretProviderRepository.save(provider);

            log.info("Successfully created secret provider with name: {}", request.getName());
            return savedProvider;
        } catch (DuplicateKeyException e) {
            throw new AlreadyExistsException("A secret provider with the same name " + request.getName() + " already exists.");
        }
    }

    @Override
    @Transactional
    public SecretProvider updateProvider(String name, SecretProviderRequest request) {
        log.debug("Updating secret provider with name: {}", name);

        // Check if the provider exists
        SecretProvider existingProvider = secretProviderRepository.findByName(name)
                .orElseThrow(() -> new NotFoundException("Secret provider not found with name: " + name));
        
        // Update the provider with new values
        secretProviderMapper.updateSecretProviderFromRequest(request, existingProvider);
        secretEncryptionUtil.encrypt(existingProvider.getAuthAttributes(), kmsConfig.getDefaultKeyId());
        SecretProvider updatedProvider = secretProviderRepository.save(existingProvider);
        
        log.info("Successfully updated secret provider with name: {}", name);
        return updatedProvider;
    }
}
