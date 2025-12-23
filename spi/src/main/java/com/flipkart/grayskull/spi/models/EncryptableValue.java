package com.flipkart.grayskull.spi.models;

import com.flipkart.grayskull.spi.EncryptionService;
import io.micrometer.common.util.StringUtils;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public abstract class EncryptableValue {
    private String kmsKeyId;
    private boolean encrypted;

    public void encrypt(EncryptionService encryptionService) {
        if (StringUtils.isEmpty(kmsKeyId)) {
            throw new IllegalStateException("kmsKeyId cannot be empty. please set kmsKeyId before encrypting");
        }
        if (!encrypted) {
            encrypt(encryptionService, kmsKeyId);
            encrypted = true;
        }
    }

    public void decrypt(EncryptionService encryptionService) {
        if (StringUtils.isEmpty(kmsKeyId)) {
            throw new IllegalStateException("kmsKeyId cannot be empty. please set kmsKeyId before decrypting");
        }
        if (encrypted) {
            decrypt(encryptionService, kmsKeyId);
            encrypted = false;
        }
    }

    protected abstract void encrypt(EncryptionService encryptionService, String kmsKeyId);

    protected abstract void decrypt(EncryptionService encryptionService, String kmsKeyId);
}
