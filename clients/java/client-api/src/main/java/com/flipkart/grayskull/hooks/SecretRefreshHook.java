package com.flipkart.grayskull.hooks;

import com.flipkart.grayskull.models.SecretValue;

/**
 * Functional interface for callbacks that are invoked when a secret is updated.
 * <p>
 * This hook is registered via {@link com.flipkart.grayskull.GrayskullClient#registerRefreshHook(String, SecretRefreshHook)}
 * and will be called asynchronously when the monitored secret is updated on the server.
 * </p>
 */
@FunctionalInterface
public interface SecretRefreshHook {
    void onUpdate(SecretValue secret) throws Exception;
}

