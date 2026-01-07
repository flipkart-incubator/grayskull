package com.flipkart.grayskull.spi.models;

import com.flipkart.grayskull.spi.EncryptionService;
import com.flipkart.grayskull.spi.Sensitive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Getter
@Setter
public abstract class EncryptableValue {

    private static final Map<Class<? extends EncryptableValue>, List<Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    private String kmsKeyId;
    private boolean encrypted;

    public void encrypt(EncryptionService encryptionService) {
        if (!StringUtils.hasLength(kmsKeyId)) {
            throw new IllegalStateException("kmsKeyId cannot be empty. please set kmsKeyId before encrypting");
        }
        if (!encrypted) {
            for (Field field : getSensitiveFields()) {
                String plainText = (String) ReflectionUtils.getField(field, this);
                String cipherText = encryptionService.encrypt(plainText, kmsKeyId);
                ReflectionUtils.setField(field, this, cipherText);
            }
            encrypted = true;
        }
    }

    public void decrypt(EncryptionService encryptionService) {
        if (!StringUtils.hasLength(kmsKeyId)) {
            throw new IllegalStateException("kmsKeyId cannot be empty. please set kmsKeyId before decrypting");
        }
        if (encrypted) {
            for (Field field : getSensitiveFields()) {
                String cipherText = (String) ReflectionUtils.getField(field, this);
                String plainText = encryptionService.decrypt(cipherText, kmsKeyId);
                ReflectionUtils.setField(field, this, plainText);
            }
            encrypted = false;
        }
    }

    private List<Field> getSensitiveFields() {
        Class<? extends EncryptableValue> cls = this.getClass();
        if (FIELD_CACHE.containsKey(cls)) {
            return FIELD_CACHE.get(cls);
        }
        List<Field> sensitiveFields = new ArrayList<>();
        for (Field field : cls.getDeclaredFields()) {
            if (field.getType().equals(String.class) && field.isAnnotationPresent(Sensitive.class)) {
                field.setAccessible(true);
                sensitiveFields.add(field);
            }
        }
        sensitiveFields = Collections.unmodifiableList(sensitiveFields);
        FIELD_CACHE.put(cls, sensitiveFields);
        return sensitiveFields;
    }

}
