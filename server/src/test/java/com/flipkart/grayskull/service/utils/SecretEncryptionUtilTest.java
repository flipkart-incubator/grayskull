package com.flipkart.grayskull.service.utils;

import com.flipkart.grayskull.spi.EncryptionService;
import com.flipkart.grayskull.spi.models.EncryptableValue;
import com.flipkart.grayskull.spi.models.SecretData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class SecretEncryptionUtilTest {

    private final EncryptionService encryptionService = mock(EncryptionService.class);

    private final SecretEncryptionUtil secretEncryptionUtil = new SecretEncryptionUtil(encryptionService);

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
    void encrypt_EncryptedValue() {
        EncryptableValue encryptedValue = mock();

        secretEncryptionUtil.encrypt(encryptedValue, "key1");

        verify(encryptedValue).setKmsKeyId("key1");
        verify(encryptedValue).encrypt(encryptionService);
    }

}
