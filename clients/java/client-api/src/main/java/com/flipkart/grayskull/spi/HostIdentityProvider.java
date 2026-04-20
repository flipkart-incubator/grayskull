package com.flipkart.grayskull.spi;

/**
 * SPI for supplying a string that identifies the host running the Grayskull
 * client, sent to the server as the {@code X-Grayskull-Host-Identification}
 * header on data-plane calls ({@code getSecret} and the batch-poll endpoint).
 * <p>
 * The OSS distribution ships a hostname-based default implementation. Consumers
 * that need stronger, verifiable identity (e.g. a Kubernetes service-account
 * token signed by the cluster) can provide their own implementation and pass it
 * into the client at construction time.
 * <p>
 * <b>Contract:</b>
 * <ul>
 *   <li>Implementations MUST be thread-safe; {@link #getHostIdentification()}
 *       may be invoked from multiple threads concurrently on every request.</li>
 *   <li>Implementations MUST be fast (treat it like reading a cached value);
 *       anything expensive should be cached internally.</li>
 *   <li>Implementations MUST NOT return {@code null}; they should return an empty
 *       string if no identity can be determined, in which case the SDK will
 *       omit the header. Returning a non-empty value causes the SDK to send
 *       it verbatim as the header value.</li>
 *   <li>Returned values MUST NOT contain CR/LF characters (HTTP header-injection
 *       guard); the SDK will sanitise defensively, but callers should not rely
 *       on that.</li>
 * </ul>
 */
@FunctionalInterface
public interface HostIdentityProvider {

    /**
     * Returns the identity string to send on data-plane calls, or an empty string
     * when no identity is available. Never {@code null}.
     */
    String getHostIdentification();
}
