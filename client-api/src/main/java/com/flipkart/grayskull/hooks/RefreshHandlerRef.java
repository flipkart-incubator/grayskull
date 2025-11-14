package com.flipkart.grayskull.hooks;

/**
 * Handle for managing the lifecycle of a registered secret refresh hook.
 * <p>
 * This interface provides methods to inspect the status of a registered hook
 * and to unregister it when it's no longer needed. Instances are returned by
 * {@link com.flipkart.grayskull.GrayskullClient#registerRefreshHook(String, SecretRefreshHook)}.
 * </p>
 * 
 * @see com.flipkart.grayskull.GrayskullClient#registerRefreshHook(String, SecretRefreshHook)
 * @see SecretRefreshHook
 */
public interface RefreshHandlerRef {
    
    /**
     * Returns the secret reference this hook is registered for.
     * 
     * @return the secret reference in format "projectId:secretName", or empty string for no-op implementations
     */
    String getSecretRef();
    
    /**
     * Checks whether this hook is currently active and will be invoked on secret updates.
     * 
     * @return {@code true} if the hook is active, {@code false} if inactive or unregistered
     */
    boolean isActive();
    
    /**
     * Unregisters this hook, preventing future invocations.
     * <p>
     * After calling this method, {@link #isActive()} will return {@code false}.
     * This method is idempotent - calling it multiple times has no additional effect.
     * </p>
     */
    void unRegister();
}

