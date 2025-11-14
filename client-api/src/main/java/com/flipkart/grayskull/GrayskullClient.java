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
     * <p>
     * <b>Note:</b> This is currently a placeholder implementation. Hooks can be registered
     * but will not be invoked until server-sent events support is added in a future release.
     * Including this API now ensures forward compatibility when the feature is fully implemented.
     * <p>
     * When fully implemented, the hook will be called asynchronously whenever the server
     * pushes an update for the monitored secret.
     * 
     *
     * @param secretRef the secret reference to monitor, in format {@code "projectId:secretName"}
     * @param hook the callback function to execute when the secret is updated
     * @return a {@link RefreshHandlerRef} handle that can be used to unregister the hook
     * @throws IllegalArgumentException if {@code secretRef} or {@code hook} is null
     */
    RefreshHandlerRef registerRefreshHook(String secretRef, SecretRefreshHook hook);

}
