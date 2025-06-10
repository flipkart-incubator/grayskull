package com.flipkart.grayskull.spimpl.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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
        byte[] original = "SensitiveData123".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = encryptionService.encrypt(original, KEY_ID);
        byte[] decrypted = encryptionService.decrypt(encrypted, KEY_ID);
        assertArrayEquals(original, decrypted);
    }
}