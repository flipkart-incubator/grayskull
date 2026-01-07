package com.flipkart.grayskull.spi.models;

import com.flipkart.grayskull.spi.Sensitive;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public final class BasicAuthAttributes extends EncryptableValue {
    @NotNull
    @Size(max = 50)
    private String username;
    @NotNull
    @Size(max = 100)
    @Sensitive
    private String password;
}
