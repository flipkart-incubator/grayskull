package com.flipkart.grayskull.hooks;

import lombok.RequiredArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Placeholder implementation of RefreshHookHandle.
 * This allows clients to register hooks without errors, but the hooks are not invoked
 * until full long-polling support is implemented in a future version.
 */
@Slf4j
@Getter
@RequiredArgsConstructor
public final class NoOpRefreshHookHandle implements RefreshHookHandle {
    private final String secretRef;
    private volatile boolean active = true;

    @Override
    public void unRegister() {
        log.debug("Unregistering refresh hook for secret: {} (placeholder implementation)", secretRef);
        this.active = false;
    }
}
