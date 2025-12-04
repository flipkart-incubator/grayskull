package com.flipkart.grayskull.spimpl.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChaChaEncryptionServiceTest {

    private ChaChaEncryptionService encryptionService;
    private static final String KEY_ID = "key1";
    private static final String KEY = "74YlShtySBrQLeuhJD1w4bm+uYaR72tuicYHDjmelWE=";

    @BeforeEach
    void setUp() {
        KeyProperties keyProperties = new KeyProperties();
        keyProperties.setKeys(Map.of(KEY_ID, KEY));
        encryptionService = new ChaChaEncryptionService(keyProperties);
    }

    @Test
    void encryptAndDecryptReturnsOriginalData() {
        String encrypted = encryptionService.encrypt("SensitiveData123", KEY_ID);
        String decrypted = encryptionService.decrypt(encrypted, KEY_ID);
        assertEquals("SensitiveData123", decrypted);
    }
}