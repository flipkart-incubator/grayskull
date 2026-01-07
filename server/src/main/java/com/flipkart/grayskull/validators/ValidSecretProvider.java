package com.flipkart.grayskull.validators;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SecretProviderValidator.class)
public @interface ValidSecretProvider {
    String message() default "invalid secret provider";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
