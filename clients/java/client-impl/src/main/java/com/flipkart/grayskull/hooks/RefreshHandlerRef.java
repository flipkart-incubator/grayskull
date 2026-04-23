package com.flipkart.grayskull.hooks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link RefreshHandlerRef} returned by {@code registerRefreshHook}.
 */
public final class DefaultRefreshHandlerRef implements RefreshHandlerRef {

    private static final Logger log = LoggerFactory.getLogger(DefaultRefreshHandlerRef.class);

    private final String secretRef;
    private final Runnable onUnregister;
    private final AtomicBoolean active = new AtomicBoolean(true);

    public DefaultRefreshHandlerRef(String secretRef, Runnable onUnregister) {
        this.secretRef = secretRef;
        this.onUnregister = onUnregister;
    }

    @Override
    public String getSecretRef() {
        return secretRef;
    }

    @Override
    public boolean isActive() {
        return active.get();
    }

    @Override
    public void unRegister() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        onUnregister.run();
        log.debug("Unregistered refresh hook for secretRef:{}", secretRef);
    }
}
