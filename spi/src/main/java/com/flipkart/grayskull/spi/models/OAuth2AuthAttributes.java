package com.flipkart.grayskull.spi.models;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public final class OAuth2AuthAttributes {
    @NotNull
    private String audience;
    @NotNull
    private String issuerUrl;
}
