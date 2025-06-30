package com.flipkart.grayskull.audit;

import com.flipkart.grayskull.models.enums.AuditAction;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method for auditing.
 * <p>
 * The {@link AuditAspect} intercepts methods annotated with {@code @Auditable}
 * to create and persist an {@link com.flipkart.grayskull.models.db.AuditEntry}
 * upon method success or failure.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {

    /**
     * Specifies the type of action being performed.
     * @return The {@link AuditAction} representing the method's purpose.
     */
    AuditAction action();

} 