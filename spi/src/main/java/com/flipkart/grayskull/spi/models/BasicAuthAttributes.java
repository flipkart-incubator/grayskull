package com.flipkart.grayskull.spi.models;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public final class BasicAuthAttributes extends EncryptableValue {
    @NotNull
    @Size(max = 50)
    private String username;
    @NotNull
    @Size(max = 100)
    private String password;

    @Override
    protected List<Property> encryptableFields() {
        return List.of(new Property(this::getPassword, this::setPassword));
    }
}
