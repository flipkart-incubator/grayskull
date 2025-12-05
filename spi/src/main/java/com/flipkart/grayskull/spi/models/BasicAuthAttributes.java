package com.flipkart.grayskull.spi.models;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BasicAuthAttributes implements AuthAttributes {
    @NotNull
    @Size(max = 50)
    private String username;
    @NotNull
    @Size(max = 100)
    @Getter(onMethod_ = {@Sensitive})
    private String password;
}
