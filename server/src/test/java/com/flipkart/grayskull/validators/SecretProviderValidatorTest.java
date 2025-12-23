package com.flipkart.grayskull.validators;

import com.flipkart.grayskull.spi.models.SecretProvider;
import com.flipkart.grayskull.spi.repositories.SecretProviderRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecretProviderValidatorTest {
    private final SecretProviderRepository repository = mock();
    private final SecretProviderValidator validator = new SecretProviderValidator(repository);

    @ParameterizedTest
    @CsvSource({"'SELF', true", "'existingProvider', true", "'nonExistingProvider', false"})
    void isValid(String provider, boolean expected) {
        when(repository.findByName("existingProvider")).thenReturn(Optional.of(new SecretProvider()));
        when(repository.findByName("nonExistingProvider")).thenReturn(Optional.empty());

        assertEquals(expected, validator.isValid(provider, mock()));
    }
}