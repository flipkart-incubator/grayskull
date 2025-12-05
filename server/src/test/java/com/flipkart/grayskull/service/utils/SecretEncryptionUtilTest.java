package com.flipkart.grayskull.service.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.configuration.KmsConfig;
import com.flipkart.grayskull.spi.EncryptionService;
import com.flipkart.grayskull.spi.models.SecretData;
import com.flipkart.grayskull.spi.models.SecretProvider;
import com.flipkart.grayskull.spi.models.enums.AuthMechanism;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class SecretEncryptionUtilTest {

    private final EncryptionService encryptionService = mock(EncryptionService.class);
    
    private final KmsConfig kmsConfig = mock(KmsConfig.class);
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecretEncryptionUtil secretEncryptionUtil = new SecretEncryptionUtil(encryptionService, objectMapper, kmsConfig);

    static Stream<Arguments> encryptSecretDataTestCases() {
        return Stream.of(
            Arguments.of("sensitive-data", "test-key-id", "encrypted-data", 1, "Should encrypt valid private part"),
            Arguments.of(null, "key-id", null, 0, "Should not encrypt null private part"),
            Arguments.of("", "key-id", "", 0, "Should not encrypt empty private part")
        );
    }

    @ParameterizedTest(name = "{4}")
    @MethodSource("encryptSecretDataTestCases")
    void encryptSecretData_ParameterizedTest(String privatePart, String keyId, String expectedResult, int expectedEncryptionCount, String description) {
        // Given
        SecretData secretData = new SecretData();
        secretData.setPrivatePart(privatePart);
        
        when(encryptionService.encrypt(privatePart, keyId)).thenReturn(expectedResult);

        // When
        secretEncryptionUtil.encryptSecretData(secretData, keyId);
        
        // Then
        assertEquals(expectedResult, secretData.getPrivatePart());
        verify(encryptionService, times(expectedEncryptionCount)).encrypt(privatePart, keyId);
    }

    static Stream<Arguments> decryptSecretDataTestCases() {
        return Stream.of(
            Arguments.of("encrypted-data", "test-key-id", "decrypted-data", 1, "Should decrypt valid encrypted data"),
            Arguments.of(null, "key-id", null, 0, "Should not decrypt null private part"),
            Arguments.of("", "key-id", "", 0, "Should not decrypt empty private part")
        );
    }

    @ParameterizedTest(name = "{4}")
    @MethodSource("decryptSecretDataTestCases")
    void decryptSecretData_ParameterizedTest(String privatePart, String keyId, String expectedResult, int decryptionCount, String description) {
        // Given
        SecretData secretData = new SecretData();
        secretData.setPrivatePart(privatePart);
        secretData.setKmsKeyId(keyId);
        
        when(encryptionService.decrypt(privatePart, keyId)).thenReturn(expectedResult);

        // When
        secretEncryptionUtil.decryptSecretData(secretData);
        
        // Then
        assertEquals(expectedResult, secretData.getPrivatePart());
        verify(encryptionService, times(decryptionCount)).decrypt(privatePart, keyId);
    }

    @Test
    void encryptSensitiveFields_WithSecretProvider_EncryptsAuthAttributes() {
        // Given
        SecretProvider provider = SecretProvider.builder()
                .authMechanism(AuthMechanism.BASIC)
                .authAttributes(Map.of("username", "admin", "password", "secret"))
                .build();
        
        String defaultKeyId = "default-key";
        when(kmsConfig.getDefaultKeyId()).thenReturn(defaultKeyId);
        when(encryptionService.encrypt("secret", defaultKeyId)).thenReturn("encrypted-secret");
        
        // When
        secretEncryptionUtil.encryptSensitiveFields(provider);
        
        // Then
        Map<String, String> authAttributes = provider.getAuthAttributes();
        assertEquals("admin", authAttributes.get("username"));
        assertEquals("encrypted-secret", authAttributes.get("password"));
        assertEquals(defaultKeyId, authAttributes.get(SecretEncryptionUtil.KEY_ID_ATTRIBUTE));
        verify(encryptionService).encrypt("secret", defaultKeyId);
    }

    @Test
    void encryptSensitiveFields_WithNoneAuthMechanism_DoesNotEncrypt() {
        // Given
        SecretProvider provider = SecretProvider.builder()
                .authMechanism(AuthMechanism.NONE)
                .authAttributes(Map.of())
                .build();
        
        String defaultKeyId = "default-key";
        when(kmsConfig.getDefaultKeyId()).thenReturn(defaultKeyId);
        
        // When
        secretEncryptionUtil.encryptSensitiveFields(provider);
        
        // Then
        Map<String, String> authAttributes = provider.getAuthAttributes();
        assertEquals(defaultKeyId, authAttributes.get(SecretEncryptionUtil.KEY_ID_ATTRIBUTE));
        verifyNoInteractions(encryptionService);
    }
}
