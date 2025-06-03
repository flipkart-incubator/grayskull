package com.flipkart.security.grayskull.spi;

public interface EncryptionService {
    byte[] encrypt(byte[] data, String keyId);
    byte[] decrypt(byte[] data, String keyId);
}
