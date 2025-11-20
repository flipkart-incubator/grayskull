package com.flipkart.grayskull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.flipkart.grayskull.auth.GrayskullAuthHeaderProvider;
import com.flipkart.grayskull.constants.MDCKeys;
import com.flipkart.grayskull.metrics.MetricsPublisher;
import com.flipkart.grayskull.models.GrayskullClientConfiguration;
import com.flipkart.grayskull.models.response.HttpResponse;
import com.flipkart.grayskull.models.SecretValue;
import com.flipkart.grayskull.models.exceptions.GrayskullException;
import com.flipkart.grayskull.hooks.NoOpRefreshHandlerRef;
import com.flipkart.grayskull.hooks.RefreshHandlerRef;
import com.flipkart.grayskull.hooks.SecretRefreshHook;
import com.flipkart.grayskull.models.response.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of the Grayskull client.
 * <p>
 * This class provides the main functionality for interacting with the Grayskull
 * secret management service, including fetching secrets and managing refresh hooks.
 * </p>
 */
public final class GrayskullClientImpl implements GrayskullClient {
    private static final Logger log = LoggerFactory.getLogger(GrayskullClientImpl.class);
    private static final TypeReference<Response<SecretValue>> SECRET_VALUE_TYPE_REFERENCE = 
            new TypeReference<Response<SecretValue>>() {};
    
    private final String baseUrl;
    private final GrayskullAuthHeaderProvider authHeaderProvider;
    private final GrayskullClientConfiguration grayskullClientConfiguration;
    private final GrayskullHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final MetricsPublisher metricsPublisher;

    /**
     * Creates a new Grayskull client implementation.
     *
     * @param authHeaderProvider provider for authentication headers (must not be null)
     * @param grayskullClientConfiguration configuration properties (must not be null)
     * @throws IllegalArgumentException if authHeaderProvider or grayskullClientConfiguration is null
     */
    public GrayskullClientImpl(GrayskullAuthHeaderProvider authHeaderProvider, GrayskullClientConfiguration grayskullClientConfiguration) {
        
        if (authHeaderProvider == null) {
            throw new IllegalArgumentException("authHeaderProvider cannot be null");
        }
        if (grayskullClientConfiguration == null) {
            throw new IllegalArgumentException("grayskullClientConfiguration cannot be null");
        }
        
        this.baseUrl = grayskullClientConfiguration.getHost();
        this.authHeaderProvider = authHeaderProvider;
        this.grayskullClientConfiguration = grayskullClientConfiguration;
        this.httpClient = new GrayskullHttpClient(authHeaderProvider, grayskullClientConfiguration);
        
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new ParameterNamesModule());
        
        // Initialize metrics publisher if enabled
        this.metricsPublisher = grayskullClientConfiguration.isMetricsEnabled() ? new MetricsPublisher() : null;
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
        String requestId = generateRequestId();
        MDC.put(MDCKeys.GRAYSKULL_REQUEST_ID, requestId);

        long startTime = System.nanoTime();
        
        int statusCode = 0;

        try {
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

            // Put context in MDC for automatic inclusion in all log statements
            MDC.put(MDCKeys.PROJECT_ID, projectId);
            MDC.put(MDCKeys.SECRET_NAME, secretName);

            log.debug("[RequestId:{}] Fetching secret", requestId);

            // URL encode the path parameters to handle special characters (spaces, slashes, etc.)
            String encodedProjectId = urlEncode(projectId);
            String encodedSecretName = urlEncode(secretName);
            String url = baseUrl + String.format("/v1/projects/%s/secrets/%s/data", encodedProjectId, encodedSecretName);
            
            // Fetch the secret with automatic retry logic
            HttpResponse httpResponse = httpClient.doGetWithRetry(url);
            statusCode = httpResponse.getStatusCode();
            
            Response<SecretValue> response = objectMapper.readValue(httpResponse.getBody(), SECRET_VALUE_TYPE_REFERENCE);
            SecretValue secretValue = response.getData();

            if (secretValue == null) {
                throw new GrayskullException(500, "No data in response");
            }
            
            return secretValue;
            
        } catch (JsonProcessingException e) {
            // JSON parsing errors are not retryable - they indicate a permanent problem
            throw new GrayskullException("Failed to parse response: ", e);
        } catch (GrayskullException e) {
            statusCode = e.getStatusCode();
            throw e;
        } finally {
            if (metricsPublisher != null) {
                long duration = System.nanoTime() - startTime;
                long durationMs = TimeUnit.NANOSECONDS.toMillis(duration);
                
                metricsPublisher.recordRequest("getSecret." + secretRef, statusCode, durationMs);
            }
            
            // Clean up MDC context
            MDC.remove(MDCKeys.PROJECT_ID);
            MDC.remove(MDCKeys.SECRET_NAME);
            MDC.remove(MDCKeys.GRAYSKULL_REQUEST_ID);
        }
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
    public RefreshHandlerRef registerRefreshHook(String secretRef, SecretRefreshHook hook) {
        String requestId = generateRequestId();
        MDC.put(MDCKeys.GRAYSKULL_REQUEST_ID, requestId);

        if (secretRef == null || secretRef.isEmpty()) {
            throw new IllegalArgumentException("secretRef cannot be null or empty");
        }
        if (hook == null) {
            throw new IllegalArgumentException("hook cannot be null");
        }
        
        log.debug("[RequestId:{}] Registering refresh hook (placeholder implementation)", requestId);
        
        // TODO: Implement actual hook invocation when server-side events support is added
        return NoOpRefreshHandlerRef.INSTANCE;
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

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new GrayskullException(500, "Failed to URL encode value: " + value, e);
        }
    }

    private String generateRequestId() {
        return UUID.randomUUID().toString();
    }
}
