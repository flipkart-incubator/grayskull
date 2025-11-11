package com.flipkart.grayskull;

import com.flipkart.grayskull.hooks.RefreshHookHandle;
import com.flipkart.grayskull.hooks.SecretRefreshHook;
import com.flipkart.grayskull.models.SecretValue;

public interface GrayskullClient extends AutoCloseable {

    SecretValue getSecret(String secretRef);

    RefreshHookHandle registerRefreshHook(String secretRef, SecretRefreshHook hook);

    @Override
    void close();
}
