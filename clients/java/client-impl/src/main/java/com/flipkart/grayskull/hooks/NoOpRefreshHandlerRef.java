package com.flipkart.grayskull.hooks;

import lombok.extern.slf4j.Slf4j;

/**
 * Placeholder implementation of RefreshHandlerRef.
 * This allows clients to register hooks without errors, but the hooks are not invoked
 * until full server sent events support is implemented in a future version.
 */
@Slf4j
public final class NoOpRefreshHandlerRef implements RefreshHandlerRef {
    
    /**
     * Singleton instance of the no-op refresh hook handle.
     */
    public static final NoOpRefreshHandlerRef INSTANCE = new NoOpRefreshHandlerRef();
    
    private NoOpRefreshHandlerRef() {
        // Private constructor to prevent instantiation
    }

    @Override
    public String getSecretRef() {
        return "";
    }

    @Override
    public boolean isActive() {
        return false;
    }

    @Override
    public void unRegister() {
        log.debug("Unregister called on no-op refresh hook handle (placeholder implementation)");
        // No-op
    }
}
