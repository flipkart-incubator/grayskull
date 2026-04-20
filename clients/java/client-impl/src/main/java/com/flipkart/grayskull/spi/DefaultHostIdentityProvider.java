package com.flipkart.grayskull.spi;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default, OSS-friendly implementation of {@link HostIdentityProvider}.
 * <p>
 * Resolves the local hostname once at construction time via
 * {@link InetAddress#getLocalHost()} and caches it. If the hostname cannot be
 * resolved (e.g. misconfigured DNS on a minimal container) it falls back to
 * {@code "unknown-host"} so we never return {@code null} and never let a header
 * value leak an exception trail.
 * <p>
 * This class intentionally carries no Kubernetes dependency; production
 * deployments that need a verifiable identity should supply their own
 * {@link HostIdentityProvider} (for instance, one that reads a projected
 * service-account token) at client construction time.
 */
public final class DefaultHostIdentityProvider implements HostIdentityProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultHostIdentityProvider.class);
    private static final String UNKNOWN = "unknown-host";

    private final String hostIdentification;

    public DefaultHostIdentityProvider() {
        this(DefaultHostIdentityProvider::systemHostname);
    }

    // Package-private seam for tests to force the fallback branch without
    // needing to mock InetAddress at the bytecode level.
    DefaultHostIdentityProvider(HostnameResolver resolver) {
        this.hostIdentification = resolveOrFallback(resolver);
    }

    @Override
    public String getHostIdentification() {
        return hostIdentification;
    }

    private static String resolveOrFallback(HostnameResolver resolver) {
        try {
            String name = resolver.resolve();
            if (name == null || name.trim().isEmpty()) {
                return UNKNOWN;
            }
            return name.trim();
        } catch (UnknownHostException e) {
            // Explicitly benign: we fall back to a sentinel so the caller can
            // still send requests; the server will just see no meaningful id.
            log.debug("Unable to resolve local hostname; using fallback identity", e);
            return UNKNOWN;
        }
    }

    private static String systemHostname() throws UnknownHostException {
        return InetAddress.getLocalHost().getHostName();
    }

    @FunctionalInterface
    interface HostnameResolver {
        String resolve() throws UnknownHostException;
    }
}
