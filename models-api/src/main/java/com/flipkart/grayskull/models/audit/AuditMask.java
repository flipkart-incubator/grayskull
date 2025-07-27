package com.flipkart.grayskull.models.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field that should be masked in audit logs.
 * Fields annotated with {@code @AuditMask} will have their values
 * replaced by a placeholder during serialization for auditing purposes,
 * preventing sensitive data from being exposed.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface AuditMask {
} 