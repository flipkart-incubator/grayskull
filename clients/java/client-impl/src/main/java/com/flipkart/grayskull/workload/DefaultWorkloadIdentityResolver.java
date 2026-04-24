package com.flipkart.grayskull.workload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.concurrent.Callable;

/** Default {@link WorkloadIdentityResolver}: local hostname, resolved once in the constructor. */
public class DefaultWorkloadIdentityResolver implements WorkloadIdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultWorkloadIdentityResolver.class);
    static final String UNKNOWN_HOST = "UNKNOWN";

    private final String resolvedIdentity;

    public DefaultWorkloadIdentityResolver() {
        this((Callable<String>) () -> InetAddress.getLocalHost().getHostName());
    }

    // Package-private seam for tests.
    DefaultWorkloadIdentityResolver(Callable<String> hostnameSupplier) {
        String hostname = UNKNOWN_HOST;
        try {
            String resolved = hostnameSupplier.call();
            if (resolved != null && !resolved.trim().isEmpty()) {
                hostname = resolved;
            }
        } catch (Exception e) {
            log.warn("Could not resolve local hostname for telemetry.", e);
        }
        this.resolvedIdentity = hostname;
    }

    @Override
    public String resolve() {
        return this.resolvedIdentity;
    }
}
