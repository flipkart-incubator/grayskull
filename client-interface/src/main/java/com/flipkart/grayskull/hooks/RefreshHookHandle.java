package com.flipkart.grayskull.hooks;

public interface RefreshHookHandle {
    String getSecretRef();
    boolean isActive();
    void unRegister();
}

