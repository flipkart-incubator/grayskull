package com.flipkart.grayskull.hooks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link RefreshHandlerRef} returned by {@code registerRefreshHook}.
 */
public final class DefaultRefreshHandlerRef implements RefreshHandlerRef {

    private static final Logger log = LoggerFactory.getLogger(DefaultRefreshHandlerRef.class);

    private final String secretRef;
    private final SecretRefreshHook hook;
    private final ConcurrentHashMap<String, SecretState> registry;
    private final AtomicBoolean active = new AtomicBoolean(true);

    public DefaultRefreshHandlerRef(String secretRef,
                                    SecretRefreshHook hook,
                                    ConcurrentHashMap<String, SecretState> registry) {
        this.secretRef = secretRef;
        this.hook = hook;
        this.registry = registry;
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
        // compute() is atomic per-key: keeps register/unregister from interleaving.
        registry.compute(secretRef, (key, state) -> {
            if (state == null) {
                return null;
            }
            state.hooks.remove(hook);
            return state.hooks.isEmpty() ? null : state;
        });
        log.debug("Unregistered refresh hook for secretRef:{}", secretRef);
    }
}
