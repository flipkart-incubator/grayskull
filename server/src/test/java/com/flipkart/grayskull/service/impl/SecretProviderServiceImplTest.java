package com.flipkart.grayskull.service.impl;

import com.flipkart.grayskull.mappers.SecretProviderMapper;
import com.flipkart.grayskull.models.dto.request.CreateSecretProviderRequest;
import com.flipkart.grayskull.models.dto.request.SecretProviderRequest;
import com.flipkart.grayskull.service.utils.SecretEncryptionUtil;
import com.flipkart.grayskull.spi.models.SecretProvider;
import com.flipkart.grayskull.spi.models.enums.AuthMechanism;
import com.flipkart.grayskull.spi.repositories.SecretProviderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecretProviderServiceImplTest {

    private final SecretProviderRepository repository = mock(SecretProviderRepository.class);
    private final SecretProviderMapper mapper = mock(SecretProviderMapper.class);
    private final SecretEncryptionUtil encryptionUtil = mock(SecretEncryptionUtil.class);
    private final SecretProviderServiceImpl service = new SecretProviderServiceImpl(repository, mapper, encryptionUtil);

    @Test
    void getProvider_ExistingProvider_ReturnsProvider() {
        // Given
        SecretProvider provider = createProvider("test-provider");
        when(repository.findByName("test-provider")).thenReturn(Optional.of(provider));

        // When
        SecretProvider result = service.getProvider("test-provider");

        // Then
        assertEquals(provider, result);
        verify(repository).findByName("test-provider");
    }

    @Test
    void getProvider_NonExistentProvider_ThrowsNotFoundException() {
        // Given
        when(repository.findByName("non-existent")).thenReturn(Optional.empty());

        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
            () -> service.getProvider("non-existent"));
        
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertTrue(exception.getReason().contains("Secret provider not found with name: non-existent"));
    }

    @Test
    void createProvider_NewProvider_CreatesSuccessfully() {
        // Given
        CreateSecretProviderRequest request = new CreateSecretProviderRequest();
        request.setName("new-provider");
        request.setAuthMechanism(AuthMechanism.BASIC);
        
        SecretProvider mappedProvider = createProvider("new-provider");
        SecretProvider savedProvider = createProvider("new-provider");
        
        when(repository.findByName("new-provider")).thenReturn(Optional.empty());
        when(mapper.requestToSecretProvider(request)).thenReturn(mappedProvider);
        when(repository.save(mappedProvider)).thenReturn(savedProvider);

        // When
        SecretProvider result = service.createProvider(request);

        // Then
        assertEquals(savedProvider, result);
        verify(repository).findByName("new-provider");
        verify(mapper).requestToSecretProvider(request);
        verify(encryptionUtil).encryptSensitiveFields(mappedProvider);
        verify(repository).save(mappedProvider);
    }

    @Test
    void createProvider_ExistingProvider_ThrowsConflictException() {
        // Given
        CreateSecretProviderRequest request = new CreateSecretProviderRequest();
        request.setName("existing-provider");
        
        when(repository.findByName("existing-provider")).thenReturn(Optional.of(createProvider("existing-provider")));

        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
            () -> service.createProvider(request));
        
        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertTrue(exception.getReason().contains("A secret provider with the same name existing-provider already exists"));
        verify(repository).findByName("existing-provider");
        verifyNoInteractions(mapper, encryptionUtil);
        verify(repository, never()).save(any());
    }

    @Test
    void updateProvider_ExistingProvider_UpdatesSuccessfully() {
        // Given
        SecretProviderRequest request = new SecretProviderRequest();
        request.setAuthMechanism(AuthMechanism.OAUTH2);
        
        SecretProvider existingProvider = createProvider("existing-provider");
        SecretProvider updatedProvider = createProvider("existing-provider");
        
        when(repository.findByName("existing-provider")).thenReturn(Optional.of(existingProvider));
        when(repository.save(existingProvider)).thenReturn(updatedProvider);

        // When
        SecretProvider result = service.updateProvider("existing-provider", request);

        // Then
        assertEquals(updatedProvider, result);
        verify(repository).findByName("existing-provider");
        verify(mapper).updateSecretProviderFromRequest(request, existingProvider);
        verify(encryptionUtil).encryptSensitiveFields(existingProvider);
        verify(repository).save(existingProvider);
    }

    @Test
    void updateProvider_NonExistentProvider_ThrowsNotFoundException() {
        // Given
        SecretProviderRequest request = new SecretProviderRequest();
        when(repository.findByName("non-existent")).thenReturn(Optional.empty());

        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
            () -> service.updateProvider("non-existent", request));
        
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertTrue(exception.getReason().contains("Secret provider not found with name: non-existent"));
        verify(repository).findByName("non-existent");
        verifyNoInteractions(mapper, encryptionUtil);
        verify(repository, never()).save(any());
    }

    private SecretProvider createProvider(String name) {
        return SecretProvider.builder()
            .name(name)
            .authMechanism(AuthMechanism.BASIC)
            .authAttributes(Map.of("username", "admin"))
            .principal("test-principal")
            .build();
    }
}
