package com.flipkart.grayskull.validators;

import com.flipkart.grayskull.spi.repositories.SecretProviderRepository;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import static com.flipkart.grayskull.service.utils.SecretProviderConstants.PROVIDER_SELF;

@Component
@AllArgsConstructor
public class SecretProviderValidator implements ConstraintValidator<ValidSecretProvider, String> {

    private SecretProviderRepository secretProviderService;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return PROVIDER_SELF.equals(value) || secretProviderService.findByName(value).isPresent();
    }
}
