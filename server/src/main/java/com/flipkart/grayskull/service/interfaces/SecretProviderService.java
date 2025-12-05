package com.flipkart.grayskull.service.interfaces;

import com.flipkart.grayskull.models.dto.request.CreateSecretProviderRequest;
import com.flipkart.grayskull.models.dto.request.SecretProviderRequest;
import com.flipkart.grayskull.spi.models.SecretProvider;

import java.util.List;

public interface SecretProviderService {
    List<SecretProvider> listProviders();
    SecretProvider getProvider(String name);
    SecretProvider createProvider(CreateSecretProviderRequest request);
    SecretProvider updateProvider(String name, SecretProviderRequest request);
}
