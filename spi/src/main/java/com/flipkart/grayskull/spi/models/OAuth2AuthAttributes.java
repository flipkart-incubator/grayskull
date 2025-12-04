package com.flipkart.grayskull.spi.models;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OAuth2AuthAttributes implements AuthAttributes {
    @NotNull
    private String audience;
    @NotNull
    private String issuerUrl;
}
