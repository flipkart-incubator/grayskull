package com.flipkart.grayskull.spi.models;

import com.flipkart.grayskull.spi.EncryptionService;
import lombok.Getter;
import lombok.Setter;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Getter
@Setter
public abstract class EncryptableValue {
    private String kmsKeyId;
    private boolean encrypted;

    public void encrypt(EncryptionService encryptionService) {
        if (StringUtils.hasLength(kmsKeyId)) {
            throw new IllegalStateException("kmsKeyId cannot be empty. please set kmsKeyId before encrypting");
        }
        if (!encrypted) {
            for (Property encryptableField : encryptableFields()) {
                encryptableField.setter.accept(encryptionService.encrypt(encryptableField.getter.get(), kmsKeyId));
            }
            encrypted = true;
        }
    }

    public void decrypt(EncryptionService encryptionService) {
        if (StringUtils.hasLength(kmsKeyId)) {
            throw new IllegalStateException("kmsKeyId cannot be empty. please set kmsKeyId before decrypting");
        }
        if (encrypted) {
            for (Property encryptableField : encryptableFields()) {
                encryptableField.setter.accept(encryptionService.decrypt(encryptableField.getter.get(), kmsKeyId));
            }
            encrypted = false;
        }
    }

    protected abstract List<Property> encryptableFields();

    protected static class Property {
        private final Supplier<String> getter;
        private final Consumer<String> setter;

        protected Property(Supplier<String> getter, Consumer<String> setter) {
            this.getter = getter;
            this.setter = setter;
        }
    }
}
