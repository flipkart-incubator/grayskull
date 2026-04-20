package com.flipkart.grayskull.hooks;

import java.util.concurrent.atomic.AtomicBoolean;

import com.flipkart.grayskull.SecretRefreshManager;

import lombok.extern.slf4j.Slf4j;

/**
 * Handle returned when a refresh hook is successfully registered.
 * <p>
 * Holds a back-reference to the owning {@link SecretRefreshManager} plus a
 * stable {@code hookId} so {@link #unRegister()} can remove precisely this
 * registration without affecting any sibling hooks registered for the same
 * secret.
 * <p>
 * {@link #unRegister()} is idempotent and thread-safe: repeated calls and
 * concurrent calls will remove at most once; subsequent {@link #isActive()}
 * checks return {@code false}.
 */
@Slf4j
public final class ActiveRefreshHandlerRef implements RefreshHandlerRef {

    private final String secretRef;
    private final long hookId;
    private final SecretRefreshManager manager;
    private final AtomicBoolean active;

    public ActiveRefreshHandlerRef(String secretRef, long hookId, SecretRefreshManager manager) {
        if (secretRef == null || secretRef.isEmpty()) {
            throw new IllegalArgumentException("secretRef cannot be null or empty");
        }
        if (manager == null) {
            throw new IllegalArgumentException("manager cannot be null");
        }
        this.secretRef = secretRef;
        this.hookId = hookId;
        this.manager = manager;
        this.active = new AtomicBoolean(true);
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
        // compareAndSet ensures exactly-once removal under concurrent unRegister
        // calls; the manager itself is idempotent but we avoid the log spam.
        if (active.compareAndSet(true, false)) {
            boolean removed = manager.unregister(secretRef, hookId);
            if (log.isDebugEnabled()) {
                log.debug("unRegister called for {} (hookId={}, removedFromManager={})",
                        secretRef, hookId, removed);
            }
        }
    }
}
