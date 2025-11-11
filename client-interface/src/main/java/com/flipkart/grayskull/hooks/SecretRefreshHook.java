package com.flipkart.grayskull.hooks;

import com.flipkart.grayskull.models.SecretValue;

@FunctionalInterface
public interface SecretRefreshHook {
    void onSecretRefresh(SecretValue secret) throws Exception;
}

