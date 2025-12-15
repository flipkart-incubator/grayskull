package com.flipkart.grayskull.spimpl.crypto;

import com.flipkart.grayskull.spi.EncryptionService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidParameterSpecException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * A default implementation of the EncryptionService interface that uses ChaCha20-Poly1305 algorithm.
 * It uses a map of keys read from the properties file to encrypt and decrypt data.
 */
@Slf4j
public class ChaChaEncryptionService implements EncryptionService {

    private static final String CHACHA_CIPHER_ALGORITHM = "ChaCha20-Poly1305/None/NoPadding";
    private static final String CHACHA_SECRETKEY_ALGORITHM = "ChaCha20";
    private static final int NONCE_SIZE_BYTES = 12;

    private final Map<String, byte[]> keys;

    public ChaChaEncryptionService(KeyProperties properties) {
        Map<String, byte[]> map = new HashMap<>();
        for (Map.Entry<String, String> entry : properties.getKeys().entrySet()) {
            map.put(entry.getKey(), Base64.getDecoder().decode(entry.getValue()));
        }
        this.keys = Map.copyOf(map);
        this.validateKeys();
    }

    public void validateKeys() {
        String s = RandomStringUtils.secure().nextAlphanumeric(10);
        for (String key : keys.keySet()) {
            log.debug("Validating key: {}", key);
            String encrypted = this.encrypt(s, key);
            this.decrypt(encrypted, key);
            /* since we are using Poly1305 which is authenticated encryption we don't need to check that decrypted data is same as original data
            if data is not same, or somehow got corrupted, it will throw exception instead of giving garbage data
            */
        }
    }

    @Override
    public String encrypt(String data, String keyId) {
        try {
            byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
            Cipher cipher = Cipher.getInstance(CHACHA_CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys.get(keyId), CHACHA_SECRETKEY_ALGORITHM));
            byte[] nonce = cipher.getParameters().getParameterSpec(IvParameterSpec.class).getIV();
            byte[] encryptedBytes = cipher.doFinal(dataBytes);
            byte[] combined = new byte[nonce.length + encryptedBytes.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(encryptedBytes, 0, combined, nonce.length, encryptedBytes.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException |
                 InvalidParameterSpecException | IllegalBlockSizeException | BadPaddingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public String decrypt(String data, String keyId) {
        try {
            byte[] dataBytes = Base64.getDecoder().decode(data);
            byte[] nonce = new byte[NONCE_SIZE_BYTES];
            byte[] cipherBytes = new byte[dataBytes.length - NONCE_SIZE_BYTES];
            System.arraycopy(dataBytes, 0, nonce, 0, NONCE_SIZE_BYTES);
            System.arraycopy(dataBytes, NONCE_SIZE_BYTES, cipherBytes, 0, cipherBytes.length);
            Cipher cipher = Cipher.getInstance(CHACHA_CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keys.get(keyId), CHACHA_SECRETKEY_ALGORITHM), new IvParameterSpec(nonce));
            return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | IllegalBlockSizeException |
                 BadPaddingException | InvalidAlgorithmParameterException e) {
            throw new IllegalArgumentException(e);
        }
    }

}
