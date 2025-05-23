package com.flipkart.security.grayskull.crypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChaChaEncryptionServiceTest {

    private ChaChaEncryptionService encryptionService;
    private static final String ENCRYPTION_KEY = "74YlShtySBrQLeuhJD1w4bm+uYaR72tuicYHDjmelWE=";
    private static final String KEY_ID = "key1";
    private static final String ENCRYPTED_KEY = "Nm7EnPxqUQmGCtMX8Nhp5PzG3qaPXzSSsJcr6HKr83nBPBlAuqz0nVyHN2dx7/iwNOCLBc7KVrYtt2Aj";

    @BeforeEach
    void setUp() {
        KeyProperties keyProperties = new KeyProperties();
        keyProperties.setKeys(Map.of(KEY_ID, ENCRYPTED_KEY));
        encryptionService = new ChaChaEncryptionService(keyProperties);
        encryptionService.decryptKeys(ENCRYPTION_KEY);
    }

    @Test
    void encryptAndDecryptReturnsOriginalData() {
        byte[] original = "SensitiveData123".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = encryptionService.encrypt(original, KEY_ID);
        byte[] decrypted = encryptionService.decrypt(encrypted, KEY_ID);
        assertArrayEquals(original, decrypted);
    }

    @Test
    void decryptKeysTwiceThrowsIllegalStateException() {
        assertThrows(IllegalStateException.class, () -> encryptionService.decryptKeys(ENCRYPTION_KEY));
    }

    @Test
    void decryptKeysWithEmptyEncryptedKeysThrowsIllegalStateException() {
        KeyProperties keyProperties = new KeyProperties();
        keyProperties.setKeys(Collections.emptyMap());
        ChaChaEncryptionService service = new ChaChaEncryptionService(keyProperties);
        assertThrows(IllegalStateException.class, () -> service.decryptKeys(ENCRYPTION_KEY));
    }
}