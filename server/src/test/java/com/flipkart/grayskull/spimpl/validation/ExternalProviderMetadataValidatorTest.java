package com.flipkart.grayskull.spimpl.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ExternalProviderMetadataValidatorTest {

    private final ExternalProviderMetadataValidator validator = new ExternalProviderMetadataValidator();

    @Test
    void validateMetadata_SelfProvider_DoesNotValidate() {
        // Given
        Map<String, Object> metadata = Map.of("someKey", "someValue");

        // When & Then - Should not throw exception
        assertDoesNotThrow(() -> validator.validateMetadata("SELF", metadata));
    }

    @Test
    void validateMetadata_ExternalProviderWithValidMetadata_Passes() {
        // Given
        Map<String, Object> metadata = Map.of(
                "revokeUrl", "https://example.com/revoke",
                "rotateUrl", "https://example.com/rotate"
        );

        // When & Then - Should not throw exception
        assertDoesNotThrow(() -> validator.validateMetadata("external-provider", metadata));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMetadataScenarios")
    void validateMetadata_ExternalProviderWithInvalidMetadata_ThrowsException(String scenarioName, Map<String, Object> metadata) {
        // When & Then
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> validator.validateMetadata("external-provider", metadata));
        
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("expected mandatory keys 'revokeUrl' and 'rotateUrl' in the providerMeta", exception.getReason());
    }

    static Stream<Arguments> invalidMetadataScenarios() {
        return Stream.of(
                Arguments.of("Missing revokeUrl", Map.of("rotateUrl", "https://example.com/rotate")),
                Arguments.of("Missing rotateUrl", Map.of("revokeUrl", "https://example.com/revoke")),
                Arguments.of("Non-string revokeUrl", Map.of(
                        "revokeUrl", 123,
                        "rotateUrl", "https://example.com/rotate"
                )),
                Arguments.of("Non-string rotateUrl", Map.of(
                        "revokeUrl", "https://example.com/revoke",
                        "rotateUrl", true
                )),
                Arguments.of("Empty metadata", Map.of())
        );
    }

}
