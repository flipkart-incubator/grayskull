package com.flipkart.grayskull.workload;

/**
 * Strategy interface for resolving the identity of the workload running the Grayskull client.
 * <p>
 * The client typically resolves this once at startup and uses the value as a default header
 */
@FunctionalInterface
public interface WorkloadIdentityResolver {
    String resolve();
}
