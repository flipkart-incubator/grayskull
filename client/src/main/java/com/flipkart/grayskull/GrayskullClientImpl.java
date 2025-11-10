package com.flipkart.grayskull;

import com.fasterxml.jackson.core.type.TypeReference;
import com.flipkart.grayskull.auth.GrayskullAuthHeaderProvider;
import com.flipkart.grayskull.exceptions.GrayskullException;
import com.flipkart.grayskull.hooks.NoOpRefreshHookHandle;
import com.flipkart.grayskull.hooks.RefreshHookHandle;
import com.flipkart.grayskull.hooks.SecretRefreshHook;
import com.flipkart.grayskull.models.GrayskullProperties;
import com.flipkart.grayskull.models.response.Response;
import com.flipkart.grayskull.models.SecretValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of the Grayskull client.
 * <p>
 * This class provides the main functionality for interacting with the Grayskull
 * secret management service, including fetching secrets and managing refresh hooks.
 * </p>
 */
public class GrayskullClientImpl implements GrayskullClient {
    private static final Logger log = LoggerFactory.getLogger(GrayskullClientImpl.class);
    
    private final String baseUrl;
    private final GrayskullAuthHeaderProvider authHeaderProvider;
    private final GrayskullProperties grayskullProperties;
    private final GrayskullHttpClient httpClient;

    /**
     * Creates a new Grayskull client implementation.
     *
     * @param authHeaderProvider provider for authentication headers
     * @param grayskullProperties configuration properties
     */
    public GrayskullClientImpl(GrayskullAuthHeaderProvider authHeaderProvider, GrayskullProperties grayskullProperties) {
        this.baseUrl = grayskullProperties.getHost();
        this.authHeaderProvider = authHeaderProvider;
        this.grayskullProperties = grayskullProperties;
        this.httpClient = new GrayskullHttpClient(authHeaderProvider, grayskullProperties);
    }
    
    /**
     * Retrieves a secret from the Grayskull server.
     * <p>
     * The secretRef should be in the format: "projectId:secretName"
     * For example: "my-project:database-password"
     * </p>
     *
     * @param secretRef the secret reference in format "projectId:secretName"
     * @return the secret value
     * @throws IllegalArgumentException if secretRef format is invalid
     * @throws RuntimeException if the secret cannot be retrieved
     */
    @Override
    public SecretValue getSecret(String secretRef) {
        if (secretRef == null || secretRef.isEmpty()) {
            throw new IllegalArgumentException("secretRef cannot be null or empty");
        }

        // Parse secretRef format: "projectId:secretName"
        String[] parts = secretRef.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "Invalid secretRef format. Expected 'projectId:secretName', got: " + secretRef);
        }

        String projectId = parts[0];
        String secretName = parts[1];

        if (projectId.isEmpty() || secretName.isEmpty()) {
            throw new IllegalArgumentException(
                    "projectId and secretName cannot be empty in secretRef: " + secretRef);
        }

        log.debug("Fetching secret: projectId={}, secretName={}", projectId, secretName);

        String url = baseUrl + String.format("/v1/projects/%s/secrets/%s/data", projectId, secretName);
        SecretValue secretValue = httpClient.doGet(url, new TypeReference<Response<SecretValue>>() {});
        
        if (secretValue == null) {
            throw new GrayskullException("No data in response");
        }

        return secretValue;
    }

    /**
     * Registers a refresh hook for a secret.
     * <p>
     * Note: This is a placeholder implementation. The hook will be registered but
     * will not be invoked until server-side long-polling support is implemented.
     * This allows applications to include hook registration code now, and it will
     * automatically work when upgrading to a future SDK version with full support.
     * </p>
     *
     * @param secretRef the secret reference to monitor
     * @param hook the hook to invoke when the secret is refreshed
     * @return a handle that can be used to check status or unregister the hook
     */
    @Override
    public RefreshHookHandle registerRefreshHook(String secretRef, SecretRefreshHook hook) {
        if (secretRef == null || secretRef.isEmpty()) {
            throw new IllegalArgumentException("secretRef cannot be null or empty");
        }
        if (hook == null) {
            throw new IllegalArgumentException("hook cannot be null");
        }
        
        log.debug("Registering refresh hook for secret: {} (placeholder implementation)", secretRef);
        
        // TODO: Implement actual hook invocation when server-side long-polling support is added
        return new NoOpRefreshHookHandle(secretRef);
    }

    /**
     * Closes the client and releases resources.
     * <p>
     * This method should be called when the client is no longer needed to properly
     * clean up HTTP connections and other resources.
     * </p>
     */
    @Override
    public void close() {
        log.info("Closing Grayskull client");
        if (httpClient != null) {
            httpClient.close();
        }
    }
}
