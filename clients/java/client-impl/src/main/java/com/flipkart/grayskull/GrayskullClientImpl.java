package com.flipkart.grayskull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.flipkart.grayskull.auth.GrayskullAuthHeaderProvider;
import com.flipkart.grayskull.constants.MDCKeys;
import com.flipkart.grayskull.hooks.ActiveRefreshHandlerRef;
import com.flipkart.grayskull.hooks.RefreshHandlerRef;
import com.flipkart.grayskull.hooks.SecretRefreshHook;
import com.flipkart.grayskull.metrics.MetricsPublisher;
import com.flipkart.grayskull.models.GrayskullClientConfiguration;
import com.flipkart.grayskull.models.SecretValue;
import com.flipkart.grayskull.models.exceptions.GrayskullException;
import com.flipkart.grayskull.models.response.HttpResponse;
import com.flipkart.grayskull.models.response.Response;
import com.flipkart.grayskull.spi.DefaultHostIdentityProvider;
import com.flipkart.grayskull.spi.HostIdentityProvider;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

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
    private final GrayskullHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SecretRefreshManager refreshManager;

    /**
     * Creates a new Grayskull client with the default {@link HostIdentityProvider}
     * (hostname-based, OSS-friendly). For stronger identity (e.g. K8s SA token),
     * use the overload that accepts a custom provider.
     */
    public GrayskullClientImpl(GrayskullAuthHeaderProvider authHeaderProvider,
                               GrayskullClientConfiguration grayskullClientConfiguration) {
        this(authHeaderProvider, grayskullClientConfiguration, new DefaultHostIdentityProvider());
    }

    /**
     * Creates a new Grayskull client implementation.
     *
     * @param authHeaderProvider           provider for authentication headers (must not be null)
     * @param grayskullClientConfiguration configuration properties (must not be null)
     * @param hostIdentityProvider         provider for the host-identification header (must not be null)
     * @throws IllegalArgumentException if any argument is null
     */
    public GrayskullClientImpl(GrayskullAuthHeaderProvider authHeaderProvider,
                               GrayskullClientConfiguration grayskullClientConfiguration,
                               HostIdentityProvider hostIdentityProvider) {

        if (authHeaderProvider == null) {
            throw new IllegalArgumentException("authHeaderProvider cannot be null");
        }
        if (grayskullClientConfiguration == null) {
            throw new IllegalArgumentException("grayskullClientConfiguration cannot be null");
        }
        if (hostIdentityProvider == null) {
            throw new IllegalArgumentException("hostIdentityProvider cannot be null");
        }

        this.baseUrl = grayskullClientConfiguration.getHost();
        this.httpClient = new GrayskullHttpClient(
                authHeaderProvider, grayskullClientConfiguration, hostIdentityProvider);

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new ParameterNamesModule());

        this.refreshManager = new SecretRefreshManager(
                httpClient,
                buildUrl("v1", "secrets", "batch"),
                objectMapper,
                grayskullClientConfiguration.getPollIntervalSeconds());

        MetricsPublisher.configure(grayskullClientConfiguration.isMetricsEnabled());
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
            String[] parts = parseSecretRef(secretRef);
            String projectId = parts[0];
            String secretName = parts[1];

            MDC.put(MDCKeys.PROJECT_ID, projectId);
            MDC.put(MDCKeys.SECRET_NAME, secretName);

            log.debug("[RequestId:{}] Fetching secret for secretRef: {}", requestId, secretRef);

            String url = buildUrl("v1", "projects", projectId, "secrets", secretName, "data");

            HttpResponse httpResponse = httpClient.doGetWithRetry(url);
            statusCode = httpResponse.getStatusCode();

            Response<SecretValue> response = objectMapper.readValue(httpResponse.getBody(), SECRET_VALUE_TYPE_REFERENCE);
            SecretValue secretValue = response.getData();

            if (secretValue == null) {
                throw new GrayskullException(500, "No data in response");
            }

            return secretValue;

        } catch (JsonProcessingException e) {
            throw new GrayskullException("Failed to parse response: ", e);
        } catch (GrayskullException e) {
            statusCode = e.getStatusCode();
            throw e;
        } finally {
            long duration = System.nanoTime() - startTime;
            long durationMs = TimeUnit.NANOSECONDS.toMillis(duration);
            MetricsPublisher.getInstance().recordRequest("getSecret." + secretRef, statusCode, durationMs);

            MDC.remove(MDCKeys.PROJECT_ID);
            MDC.remove(MDCKeys.SECRET_NAME);
            MDC.remove(MDCKeys.GRAYSKULL_REQUEST_ID);
        }
    }

    /**
     * Registers a refresh hook for a secret.
     * <p>
     * This method fetches the current secret value synchronously and invokes the hook
     * immediately, ensuring the application has the latest value at boot time. The hook
     * is then registered for ongoing polling — the SDK will check for version changes
     * at a configurable interval (default 30s) and invoke the hook when updates are detected.
     * <p>
     * Multiple hooks may be registered for the same {@code secretRef}; they will all be
     * invoked in registration order when an update is detected. Each returned
     * {@link RefreshHandlerRef#unRegister()} removes only that specific registration.
     *
     * @param secretRef the secret reference to monitor, in format "projectId:secretName"
     * @param hook the hook to invoke when the secret is refreshed
     * @return a handle that can be used to check status and unregister the hook
     */
    @Override
    public RefreshHandlerRef registerRefreshHook(String secretRef, SecretRefreshHook hook) {
        String requestId = generateRequestId();
        MDC.put(MDCKeys.GRAYSKULL_REQUEST_ID, requestId);
        try {
            // Validate upfront so we never register a malformed ref into the poller.
            parseSecretRef(secretRef);
            if (hook == null) {
                throw new IllegalArgumentException("hook cannot be null");
            }

            log.info("[RequestId:{}] Registering refresh hook for secretRef: {}", requestId, secretRef);

            SecretValue currentValue = getSecret(secretRef);

            try {
                hook.onUpdate(currentValue);
            } catch (Exception e) {
                // Initial invocation errors are surfaced to logs only — the
                // hook stays registered; we cannot unwind whatever side effect
                // the user's code may have already performed.
                log.warn("[RequestId:{}] Hook threw during initial invocation for {}", requestId, secretRef, e);
            }

            long hookId = refreshManager.register(secretRef, hook, currentValue.getDataVersion());

            return new ActiveRefreshHandlerRef(secretRef, hookId, refreshManager);

        } finally {
            MDC.remove(MDCKeys.GRAYSKULL_REQUEST_ID);
        }
    }

    /**
     * Closes the client and releases resources.
     */
    @Override
    public void close() {
        log.info("Closing Grayskull client");
        if (refreshManager != null) {
            refreshManager.shutdown();
        }
        if (httpClient != null) {
            httpClient.close();
        }
    }

    private String buildUrl(String... pathSegments) {
        HttpUrl parsedBaseUrl = HttpUrl.parse(baseUrl);
        if (parsedBaseUrl == null) {
            throw new IllegalStateException("Invalid baseUrl: " + baseUrl);
        }

        HttpUrl.Builder builder = parsedBaseUrl.newBuilder();
        for (String segment : pathSegments) {
            builder.addPathSegment(segment);
        }

        return builder.build().toString();
    }

    private static String[] parseSecretRef(String secretRef) {
        if (secretRef == null || secretRef.isEmpty()) {
            throw new IllegalArgumentException("secretRef cannot be null or empty");
        }
        String[] parts = secretRef.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "Invalid secretRef format. Expected 'projectId:secretName', got: " + secretRef);
        }
        if (parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new IllegalArgumentException(
                    "projectId and secretName cannot be empty in secretRef: " + secretRef);
        }
        return parts;
    }

    private String generateRequestId() {
        return UUID.randomUUID().toString();
    }
}
