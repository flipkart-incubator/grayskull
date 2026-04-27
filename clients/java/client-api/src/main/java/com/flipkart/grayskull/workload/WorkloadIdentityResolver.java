package com.flipkart.grayskull.workload;

/** Resolves workload identity for the {@code Grayskull-Workload} header (usually once at startup). */
@FunctionalInterface
public interface WorkloadIdentityResolver {
    String resolve();
}
