package com.flipkart.grayskull.hooks;

import com.flipkart.grayskull.models.SecretValue;

/**
 * Functional interface for callbacks that are invoked when a secret is updated.
 * <p>
 * This hook is registered via {@link com.flipkart.grayskull.GrayskullClient#registerRefreshHook(String, SecretRefreshHook)}
 * and will be called asynchronously when the monitored secret is updated on the server.
 * <p>
 * <b>Note:</b> This is currently part of a placeholder implementation. While hooks can be registered,
 * they will not be invoked until server-sent events support is added in a future release.
 */
@FunctionalInterface
public interface SecretRefreshHook {
    void onUpdate(SecretValue secret) throws Exception;
}

