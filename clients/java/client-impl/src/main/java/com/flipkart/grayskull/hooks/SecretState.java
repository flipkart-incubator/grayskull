package com.flipkart.grayskull.hooks;

import com.flipkart.grayskull.models.SecretValue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-secret runtime state in the hook registry.
 */
public final class SecretState {
    public final AtomicInteger lastKnownVersion = new AtomicInteger(0);
    public final List<SecretRefreshHook> hooks = new CopyOnWriteArrayList<>();
    public final AtomicBoolean isExecuting = new AtomicBoolean(false);
    public final AtomicReference<SecretValue> pendingUpdate = new AtomicReference<>();
}
