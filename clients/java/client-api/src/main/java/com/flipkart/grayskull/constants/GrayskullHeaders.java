package com.flipkart.grayskull.constants;

/**
 * HTTP header names used by the Grayskull client on the wire.
 */
public final class GrayskullHeaders {

    /**
     * Identity of the workload running the Grayskull client, populated from
     * the configured {@code WorkloadIdentityResolver}.
     */
    public static final String WORKLOAD = "Grayskull-Workload";

    /**
     * SDK identity; value is set by {@code GrayskullClientImpl} at construction.
     */
    public static final String USER_AGENT = "User-Agent";

    private GrayskullHeaders() {
        throw new AssertionError("Cannot instantiate constants class");
    }
}
