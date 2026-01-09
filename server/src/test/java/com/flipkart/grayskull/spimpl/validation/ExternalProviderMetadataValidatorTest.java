package com.flipkart.grayskull.spimpl.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.stream.Stream;

import static com.flipkart.grayskull.service.utils.SecretProviderConstants.*;
import static com.flipkart.grayskull.service.utils.SecretProviderConstants.ROTATE_URL_KEY;
import static org.junit.jupiter.api.Assertions.*;

class ExternalProviderMetadataValidatorTest {

    private final ExternalProviderMetadataValidator validator = new ExternalProviderMetadataValidator();

    @Test
    void validateMetadata_SelfProvider_DoesNotValidate() {
        // Given
        Map<String, Object> metadata = Map.of("someKey", "someValue");

        // When & Then - Should not throw exception
        assertDoesNotThrow(() -> validator.validateMetadata(PROVIDER_SELF, metadata));
    }

    @Test
    void validateMetadata_ExternalProviderWithValidMetadata_Passes() {
        // Given
        Map<String, Object> metadata = Map.of(
                REVOKE_URL_KEY, "https://example.com/revoke",
                ROTATE_URL_KEY, "https://example.com/rotate"
        );

        // When & Then - Should not throw exception
        assertDoesNotThrow(() -> validator.validateMetadata("external-provider", metadata));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMetadataScenarios")
    void validateMetadata_ExternalProviderWithInvalidMetadata_ThrowsException(String scenarioName, Map<String, Object> metadata, String expectedError) {
        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> validator.validateMetadata("external-provider", metadata));
        
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals(expectedError, exception.getReason());
    }

    static Stream<Arguments> invalidMetadataScenarios() {
        return Stream.of(
                Arguments.of("Missing revokeUrl", Map.of(ROTATE_URL_KEY, "https://example.com/rotate"), "expected mandatory key 'revokeUrl' in the providerMeta"),
                Arguments.of("Missing rotateUrl", Map.of(REVOKE_URL_KEY, "https://example.com/revoke"), "expected mandatory key 'rotateUrl' in the providerMeta"),
                Arguments.of("Non-string revokeUrl", Map.of(
                        REVOKE_URL_KEY, 123,
                        ROTATE_URL_KEY, "https://example.com/rotate"
                ), "expected mandatory key 'revokeUrl' in the providerMeta"),
                Arguments.of("Non-string rotateUrl", Map.of(
                        REVOKE_URL_KEY, "https://example.com/revoke",
                        ROTATE_URL_KEY, true
                ), "expected mandatory key 'rotateUrl' in the providerMeta"),
                Arguments.of("Empty metadata", Map.of(), "expected mandatory key 'revokeUrl' in the providerMeta"),
                Arguments.of("Invalid url", Map.of(
                        REVOKE_URL_KEY, "invalid-url",
                        ROTATE_URL_KEY, "https://example.com/rotate"
                ), "invalid url: invalid-url for the providerMeta key: revokeUrl"),
                Arguments.of("Invalid url", Map.of(
                        REVOKE_URL_KEY, "https://example.com/revoke",
                        ROTATE_URL_KEY, "invalid-url"
                ), "invalid url: invalid-url for the providerMeta key: rotateUrl")
        );
    }

}
