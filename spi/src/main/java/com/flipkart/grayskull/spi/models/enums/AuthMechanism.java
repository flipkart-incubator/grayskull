package com.flipkart.grayskull.spi.models.enums;

import com.flipkart.grayskull.spi.models.AuthAttributes;
import com.flipkart.grayskull.spi.models.BasicAuthAttributes;
import com.flipkart.grayskull.spi.models.NoneAuthAttributes;
import com.flipkart.grayskull.spi.models.OAuth2AuthAttributes;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum AuthMechanism {
    NONE(NoneAuthAttributes.class),
    BASIC(BasicAuthAttributes.class),
    OAUTH2(OAuth2AuthAttributes.class);

    private final Class<? extends AuthAttributes> attributesClass;
}
