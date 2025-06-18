package com.flipkart.grayskull.spimpl.crypto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

@Validated
@Getter
@Setter
public class KeyProperties {
    @NotEmpty
    private Map<String, String> keys;
}