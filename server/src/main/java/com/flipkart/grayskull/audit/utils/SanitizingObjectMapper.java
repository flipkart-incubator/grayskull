package com.flipkart.grayskull.audit.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * A factory for creating a pre-configured {@link ObjectMapper} for sanitizing audit data.
 * The created mapper is equipped with a {@link AuditMaskBeanSerializerModifier} to automatically
 * mask fields annotated with {@link com.flipkart.grayskull.audit.AuditMask}.
 * It also includes the {@link JavaTimeModule} to ensure correct serialization of Java 8 date/time types.
 */
public class SanitizingObjectMapper {

    /**
     * Creates and configures an {@link ObjectMapper} with sanitization capabilities.
     *
     * @return A new, configured {@link ObjectMapper} instance.
     */
    public static ObjectMapper create() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        SimpleModule module = new SimpleModule();
        module.setSerializerModifier(new AuditMaskBeanSerializerModifier());
        mapper.registerModule(module);

        return mapper;
    }
} 