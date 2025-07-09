package com.flipkart.grayskull.audit.utils;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.flipkart.grayskull.models.audit.AuditMask;

import java.util.List;

/**
 * A {@link BeanSerializerModifier} that enables field masking for audit logging.
 * This modifier inspects Java beans for properties annotated with {@link AuditMask}.
 * If an annotation is present, it assigns the {@link AuditMaskingSerializer} to the property,
 * ensuring its value is masked during JSON serialization.
 */
public class AuditMaskBeanSerializerModifier extends BeanSerializerModifier {

    /**
     * Modifies the properties of a bean to apply the masking serializer where needed.
     * It iterates through all bean properties and, if a property is annotated with {@link AuditMask},
     * assigns the {@link AuditMaskingSerializer} to it.
     *
     * @param config       The serialization configuration.
     * @param beanDesc     The description of the bean being serialized.
     * @param beanProperties A list of writers for the bean's properties.
     * @return The (potentially modified) list of bean property writers.
     */
    @Override
    public List<BeanPropertyWriter> changeProperties(SerializationConfig config, BeanDescription beanDesc,
                                                     List<BeanPropertyWriter> beanProperties) {
        for (BeanPropertyWriter writer : beanProperties) {
            if (writer.getAnnotation(AuditMask.class) != null) {
                writer.assignSerializer(new AuditMaskingSerializer());
            }
        }
        return beanProperties;
    }
} 