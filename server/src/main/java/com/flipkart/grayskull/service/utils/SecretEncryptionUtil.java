package com.flipkart.grayskull.service.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.configuration.KmsConfig;
import com.flipkart.grayskull.spi.models.AuthAttributes;
import com.flipkart.grayskull.spi.models.SecretData;
import com.flipkart.grayskull.spi.EncryptionService;
import com.flipkart.grayskull.spi.models.SecretProvider;
import com.flipkart.grayskull.spi.models.Sensitive;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.BeanInfo;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SecretEncryptionUtil {

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {
    };
    public static final String KEY_ID_ATTRIBUTE = "kmsKeyId";

    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;
    private final KmsConfig kmsConfig;

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

    private boolean isSensitive(PropertyDescriptor propertyDescriptor) {
        boolean sensitive = false;
        if (propertyDescriptor.getReadMethod() != null) {
            sensitive = propertyDescriptor.getReadMethod().isAnnotationPresent(Sensitive.class);
        }
        if (!sensitive && propertyDescriptor.getWriteMethod() != null) {
            sensitive = propertyDescriptor.getWriteMethod().isAnnotationPresent(Sensitive.class);
        }
        return sensitive;
    }

    public void encryptSensitiveFields(Object object, String keyId) {
        try {
            BeanInfo beanInfo = Introspector.getBeanInfo(object.getClass());
            for (PropertyDescriptor propertyDescriptor : beanInfo.getPropertyDescriptors()) {
                if (isSensitive(propertyDescriptor) && propertyDescriptor.getPropertyType().equals(String.class)) {
                    propertyDescriptor.getWriteMethod().invoke(object, encryptionService.encrypt((String) propertyDescriptor.getReadMethod().invoke(object), keyId));
                }
            }
        } catch (IntrospectionException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Failed to encrypt sensitive fields: " + e.getMessage(), e);
        }
    }

    public void encryptSensitiveFields(SecretProvider provider) {
        AuthAttributes authAttributes = objectMapper.convertValue(provider.getAuthAttributes(), provider.getAuthMechanism().getAttributesClass());
        encryptSensitiveFields(authAttributes, kmsConfig.getDefaultKeyId());
        Map<String, String> encryptedAttributes = objectMapper.convertValue(authAttributes, MAP_TYPE);
        encryptedAttributes.put(KEY_ID_ATTRIBUTE, kmsConfig.getDefaultKeyId());
        provider.setAuthAttributes(encryptedAttributes);
    }
}