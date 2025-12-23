package com.flipkart.grayskull.service.utils;

import com.flipkart.grayskull.spi.models.EncryptableValue;
import com.flipkart.grayskull.spi.models.SecretData;
import com.flipkart.grayskull.spi.EncryptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SecretEncryptionUtil {

    private final EncryptionService encryptionService;

    public void encryptSecretData(SecretData secretData, String keyId) {
        if (secretData.getPrivatePart() != null && !secretData.getPrivatePart().isEmpty()) {
            secretData.setPrivatePart(encryptionService.encrypt(secretData.getPrivatePart(), keyId));
            secretData.setKmsKeyId(keyId);
        }
    }

    public void decryptSecretData(SecretData secretData) {
        if (secretData.getPrivatePart() != null && !secretData.getPrivatePart().isEmpty()) {
            String decrypted = encryptionService.decrypt(secretData.getPrivatePart(), secretData.getKmsKeyId());
            secretData.setPrivatePart(decrypted);
        }
    }

    public void encrypt(Object o, String key) {
        if (o instanceof EncryptableValue value) {
            value.setKmsKeyId(key);
            value.encrypt(encryptionService);
        }
    }
}