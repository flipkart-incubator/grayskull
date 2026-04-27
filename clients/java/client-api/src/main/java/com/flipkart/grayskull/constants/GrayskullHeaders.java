package com.flipkart.grayskull.constants;

/**
 * HTTP header names used by the Grayskull client on the wire.
 */
public final class GrayskullHeaders {

    /**
     * Workload identity ({@code Grayskull-Workload}), resolved at client construction.
     * Use for caller identity; not {@link #USER_AGENT}.
     */
    public static final String WORKLOAD = "Grayskull-Workload";

    /**
     * SDK product/version ({@code grayskull-java/<version>}), set at client construction.
     * Telemetry only; not for identity.
     */
    public static final String USER_AGENT = "User-Agent";

    private GrayskullHeaders() {
        throw new AssertionError("Cannot instantiate constants class");
    }
}
