package com.flipkart.grayskull.spi.models;

import com.flipkart.grayskull.spi.EncryptionService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EncryptableValueTest {

    @Test
    void encrypt_WhenKmsKeyIdIsNull_ThrowsIllegalStateException() {
        EncryptionService encryptionService = mock(EncryptionService.class);
        BasicAuthAttributes value = new BasicAuthAttributes();
        value.setPassword("plain");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> value.encrypt(encryptionService));
        assertEquals("kmsKeyId cannot be empty. please set kmsKeyId before encrypting", ex.getMessage());
        verifyNoInteractions(encryptionService);
    }

    @Test
    void decrypt_WhenKmsKeyIdIsBlank_ThrowsIllegalStateException() {
        EncryptionService encryptionService = mock(EncryptionService.class);
        BasicAuthAttributes value = new BasicAuthAttributes();
        value.setPassword("encrypted");
        value.setKmsKeyId("");
        value.setEncrypted(true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> value.decrypt(encryptionService));
        assertEquals("kmsKeyId cannot be empty. please set kmsKeyId before decrypting", ex.getMessage());
        verifyNoInteractions(encryptionService);
    }

    @Test
    void encrypt_WhenNotAlreadyEncrypted_EncryptsSensitiveFieldsAndSetsEncryptedTrue() {
        EncryptionService encryptionService = mock(EncryptionService.class);
        BasicAuthAttributes value = new BasicAuthAttributes();
        value.setPassword("plain");
        value.setKmsKeyId("key-1");

        when(encryptionService.encrypt("plain", "key-1")).thenReturn("cipher");

        value.encrypt(encryptionService);

        assertTrue(value.isEncrypted());
        assertEquals("cipher", value.getPassword());
        verify(encryptionService, times(1)).encrypt("plain", "key-1");
        verify(encryptionService, never()).decrypt(anyString(), anyString());
    }

    @Test
    void encrypt_WhenAlreadyEncrypted_IsNoOp() {
        EncryptionService encryptionService = mock(EncryptionService.class);
        BasicAuthAttributes value = new BasicAuthAttributes();
        value.setPassword("cipher");
        value.setKmsKeyId("key-1");
        value.setEncrypted(true);

        value.encrypt(encryptionService);

        assertTrue(value.isEncrypted());
        assertEquals("cipher", value.getPassword());
        verifyNoInteractions(encryptionService);
    }

    @Test
    void decrypt_WhenEncrypted_DecryptsSensitiveFieldsAndSetsEncryptedFalse() {
        EncryptionService encryptionService = mock(EncryptionService.class);
        BasicAuthAttributes value = new BasicAuthAttributes();
        value.setPassword("cipher");
        value.setKmsKeyId("key-1");
        value.setEncrypted(true);

        when(encryptionService.decrypt("cipher", "key-1")).thenReturn("plain");

        value.decrypt(encryptionService);

        assertFalse(value.isEncrypted());
        assertEquals("plain", value.getPassword());
        verify(encryptionService, times(1)).decrypt("cipher", "key-1");
        verify(encryptionService, never()).encrypt(anyString(), anyString());
    }

    @Test
    void decrypt_WhenNotEncrypted_IsNoOp() {
        EncryptionService encryptionService = mock(EncryptionService.class);
        BasicAuthAttributes value = new BasicAuthAttributes();
        value.setPassword("plain");
        value.setKmsKeyId("key-1");
        value.setEncrypted(false);

        value.decrypt(encryptionService);

        assertFalse(value.isEncrypted());
        assertEquals("plain", value.getPassword());
        verifyNoInteractions(encryptionService);
    }
}
