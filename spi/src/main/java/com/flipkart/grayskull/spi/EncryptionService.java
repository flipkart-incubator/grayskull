package com.flipkart.grayskull.spi;

/**
 * Interface for an encryption service in the Grayskull security framework.
 * It is used to encrypt secrets before storing them in the database and decrypt them when needed.
 */
public interface EncryptionService {

    /**
     * Encrypt the data. It accepts byte array and returns byte array so that
     * 1. It can be used with binary data
     * 2. Secret data can be cleared from memory after encryption
     * @param data data to encrypt
     * @param keyId key id to use for encryption
     * @return encrypted data
     */
    byte[] encrypt(byte[] data, String keyId);

    /**
     * Decrypt the data. It accepts byte array and returns byte array so that
     * 1. It can be used with binary data
     * 2. Secret data can be cleared from memory when it is not needed anymore
     * @param data data to decrypt
     * @param keyId key id to use for decryption, this should be the same as the key id that was used for encryption
     * @return decrypted data
     */
    byte[] decrypt(byte[] data, String keyId);
}
