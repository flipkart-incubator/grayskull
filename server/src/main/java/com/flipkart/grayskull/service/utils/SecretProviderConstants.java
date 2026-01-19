package com.flipkart.grayskull.service.utils;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;

@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SecretProviderConstants {
    public static final String PROVIDER_SELF = "SELF";

    public static final String REVOKE_URL_KEY = "revokeUrl";
    public static final String ROTATE_URL_KEY = "rotateUrl";
}
