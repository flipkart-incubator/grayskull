package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.models.dto.request.CreateSecretProviderRequest;
import com.flipkart.grayskull.service.interfaces.SecretProviderService;
import com.flipkart.grayskull.spi.models.OAuth2AuthAttributes;
import com.flipkart.grayskull.spi.models.enums.AuthMechanism;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class SecretProviderControllerTest {

    @Test
    void validateAuthAttributes_WhenAttributesTypeMatches_DoesNotThrow() {
        SecretProviderService secretProviderService = mock(SecretProviderService.class);
        SecretProviderController controller = new SecretProviderController(secretProviderService);

        CreateSecretProviderRequest request = secretProviderRequest(AuthMechanism.OAUTH2, new OAuth2AuthAttributes());

        assertDoesNotThrow(() -> controller.createProvider(request));
        verify(secretProviderService).createProvider(request);
    }

    @Test
    void validateAuthAttributes_WhenAttributesTypeDoesNotMatch_ThrowsIllegalArgumentException() {
        SecretProviderService secretProviderService = mock(SecretProviderService.class);
        SecretProviderController controller = new SecretProviderController(secretProviderService);

        CreateSecretProviderRequest request = secretProviderRequest(AuthMechanism.BASIC, new Object());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> controller.createProvider(request));
        assertEquals("Invalid auth attributes for auth mechanism: BASIC", ex.getMessage());
        verify(secretProviderService, never()).createProvider(any());
    }

    private CreateSecretProviderRequest secretProviderRequest(AuthMechanism authMechanism, Object authAttributes) {
        CreateSecretProviderRequest request = new CreateSecretProviderRequest();
        request.setName("test-provider");
        request.setAuthMechanism(authMechanism);
        request.setAuthAttributes(authAttributes);
        request.setPrincipal("test-principal");
        return request;
    }
}
