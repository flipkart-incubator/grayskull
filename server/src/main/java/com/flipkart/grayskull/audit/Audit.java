package com.flipkart.grayskull.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method for auditing.
 * <p>
 * The {@link AuditAspect} intercepts methods annotated with {@code @Audit}
 * to create and persist an {@link com.flipkart.grayskull.spi.models.AuditEntry}
 * upon successful method completion. Only successful operations are audited.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audit {

    /**
     * Specifies the type of action being performed.
     * 
     * @return The {@link AuditAction} representing the method's purpose.
     */
    AuditAction action();

}
