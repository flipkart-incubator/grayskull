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

    private GrayskullHeaders() {
        throw new AssertionError("Cannot instantiate constants class");
    }
}
