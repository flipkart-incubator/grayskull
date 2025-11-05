package com.flipkart.grayskull.service.utils;

import com.flipkart.grayskull.spi.models.SecretData;
import com.flipkart.grayskull.spi.EncryptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Base64;

@Component
@RequiredArgsConstructor
public class SecretEncryptionUtil {

    private final EncryptionService encryptionService;

    public void encryptSecretData(SecretData secretData, String keyId) {
        if (secretData.getPrivatePart() != null && !secretData.getPrivatePart().isEmpty()) {
            byte[] encrypted = encryptionService.encrypt(secretData.getPrivatePart().getBytes(), keyId);
            secretData.setPrivatePart(Base64.getEncoder().encodeToString(encrypted));
            secretData.setKmsKeyId(keyId);
        }
    }

    public void decryptSecretData(SecretData secretData) {
        if (secretData.getPrivatePart() != null && !secretData.getPrivatePart().isEmpty()) {
            byte[] decrypted = encryptionService.decrypt(Base64.getDecoder().decode(secretData.getPrivatePart()),
                    secretData.getKmsKeyId());
            secretData.setPrivatePart(new String(decrypted));
        }
    }
}