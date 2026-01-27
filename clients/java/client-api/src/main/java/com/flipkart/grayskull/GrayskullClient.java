package com.flipkart.grayskull;

import com.flipkart.grayskull.hooks.RefreshHandlerRef;
import com.flipkart.grayskull.hooks.SecretRefreshHook;
import com.flipkart.grayskull.models.SecretValue;

/**
 * Main client interface for interacting with the Grayskull secret management service.
 * <p>
 * This client provides a simple API for retrieving secrets and registering hooks to monitor
 * secret updates. It implements {@link AutoCloseable} for proper resource management.

 * <b>Thread Safety:</b> This interface and its implementations are thread-safe and can be
 * safely used from multiple threads concurrently.
 *
 * @see SecretValue
 * @see SecretRefreshHook
 * @see RefreshHandlerRef
 */
public interface GrayskullClient extends AutoCloseable {

    SecretValue getSecret(String secretRef);

    /**
     * Registers a callback hook to be invoked when a secret is updated. 
     * The hook will be called asynchronously whenever the server pushes an update for the monitored secret.
     * 
     * @param secretRef the secret reference to monitor, in format {@code "projectId:secretName"}
     * @param hook the callback function to execute when the secret is updated
     * @return a {@link RefreshHandlerRef} handle that can be used to unregister the hook
     * @throws IllegalArgumentException if {@code secretRef} or {@code hook} is null
     */
    RefreshHandlerRef registerRefreshHook(String secretRef, SecretRefreshHook hook);

    /**
     * Closes this client and releases all resources.
     */
    @Override
    void close();

}
