package com.flipkart.grayskull.audit.utils;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * A Jackson serializer that replaces the value of a field with a predefined mask.
 * This is used to hide sensitive information in audit entries.
 * The serializer writes a static placeholder string ({@code MASKED}) instead of the actual field value.
 */
public class AuditMaskingSerializer extends JsonSerializer<Object> {

    private static final String MASKED_VALUE = "MASKED";

    /**
     * Serializes the given value by writing a masked placeholder.
     *
     * @param value       The object to serialize (will be ignored).
     * @param gen         The JSON generator to write to.
     * @param serializers The provider for serializers.
     * @throws IOException If an I/O error occurs.
     */
    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeString(MASKED_VALUE);
    }
} 