package com.flipkart.grayskull.spi.models;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public final class NoneAuthAttributes {
    /**
     * Explanation on why none is used as auth mechanism
     */
    @NotNull
    private String description;
}
