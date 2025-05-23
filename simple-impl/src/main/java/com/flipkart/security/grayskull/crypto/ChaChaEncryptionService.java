package com.flipkart.security.grayskull.crypto;

import com.flipkart.security.grayskull.spi.EncryptionService;
import org.springframework.stereotype.Component;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidParameterSpecException;
import java.util.Base64;
import java.util.Map;

@Component
public class ChaChaEncryptionService implements EncryptionService {

    private static final String CHACHA_ALGORITHM = "ChaCha20-Poly1305";

    private Map<String, String> encryptedKeys;
    private Map<String, byte[]> decryptedKeys;

    public ChaChaEncryptionService(KeyProperties properties) {
        this.encryptedKeys = properties.getKeys();
    }

    public void decryptKeys(String encryptionKey) {
        if (encryptedKeys == null || encryptedKeys.isEmpty()) {
            throw new IllegalStateException("Keys have already been decrypted");
        }
        byte[] encryptionKeyBytes = Base64.getDecoder().decode(encryptionKey);
        Map<String, byte[]> decryptedKeys = new java.util.HashMap<>();
        for (Map.Entry<String, String> entry : encryptedKeys.entrySet()) {
            String keyId = entry.getKey();
            String encryptedKey = entry.getValue();
            byte[] keyBytes = Base64.getDecoder().decode(encryptedKey);
            decryptedKeys.put(keyId, decrypt(keyBytes, encryptionKeyBytes));
        }
        this.decryptedKeys = decryptedKeys;
        this.encryptedKeys = null;
    }

    @Override
    public byte[] encrypt(byte[] data, String keyId) {
        return encrypt(data, decryptedKeys.get(keyId));
    }

    private byte[] encrypt(byte[] data, byte[] keyBytes) {
        try {
            Cipher cipher = Cipher.getInstance(CHACHA_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, CHACHA_ALGORITHM));
            byte[] nonce = cipher.getParameters().getParameterSpec(IvParameterSpec.class).getIV();
            byte[] encryptedBytes = cipher.doFinal(data);
            byte[] combined = new byte[nonce.length + encryptedBytes.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(encryptedBytes, 0, combined, nonce.length, encryptedBytes.length);
            return combined;
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException |
                 InvalidParameterSpecException | IllegalBlockSizeException | BadPaddingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public byte[] decrypt(byte[] data, String keyId) {
        return decrypt(data, decryptedKeys.get(keyId));
    }

    private byte[] decrypt(byte[] data, byte[] keyBytes) {
        try {
            byte[] nonce = new byte[12];
            byte[] cipherBytes = new byte[data.length - 12];
            System.arraycopy(data, 0, nonce, 0, 12);
            System.arraycopy(data, 12, cipherBytes, 0, cipherBytes.length);
            Cipher cipher = Cipher.getInstance(CHACHA_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, CHACHA_ALGORITHM), new IvParameterSpec(nonce));
            return cipher.doFinal(cipherBytes);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | IllegalBlockSizeException |
                 BadPaddingException | InvalidAlgorithmParameterException e) {
            throw new RuntimeException(e);
        }
    }
}
