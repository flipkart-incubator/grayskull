package com.flipkart.grayskull.validators;

import com.flipkart.grayskull.spi.repositories.SecretProviderRepository;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class SecretProviderValidator implements ConstraintValidator<ValidSecretProvider, String> {

    private SecretProviderRepository secretProviderService;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return "SELF".equals(value) || secretProviderService.findByName(value).isPresent();
    }
}
