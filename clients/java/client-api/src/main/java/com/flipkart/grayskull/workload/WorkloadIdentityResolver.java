package com.flipkart.grayskull.workload;

/**
 * Strategy interface for resolving the identity of the workload running the Grayskull client.
 * <p>
 * Implementations should resolve and cache the identity once (e.g. in the constructor),
 * since {@link #resolve()} is invoked on the request path.
 */
@FunctionalInterface
public interface WorkloadIdentityResolver {
    String resolve();
}
