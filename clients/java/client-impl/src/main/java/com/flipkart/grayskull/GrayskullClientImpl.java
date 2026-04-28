package com.flipkart.grayskull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.flipkart.grayskull.auth.GrayskullAuthHeaderProvider;
import com.flipkart.grayskull.constants.GrayskullHeaders;
import com.flipkart.grayskull.constants.MDCKeys;
import com.flipkart.grayskull.hooks.RefreshHandlerRef;
import com.flipkart.grayskull.hooks.SecretRefreshHook;
import com.flipkart.grayskull.metrics.MetricsPublisher;
import com.flipkart.grayskull.models.GrayskullClientConfiguration;
import com.flipkart.grayskull.models.SecretValue;
import com.flipkart.grayskull.models.exceptions.GrayskullException;
import com.flipkart.grayskull.models.response.HttpResponse;
import com.flipkart.grayskull.models.response.Response;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final HookRefreshPoller refreshPoller;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Creates a new Grayskull client implementation.
     *
     * @param authHeaderProvider provider for authentication headers (must not be null)
     * @param grayskullClientConfiguration configuration properties (must not be null)
     * @throws IllegalArgumentException if authHeaderProvider or grayskullClientConfiguration is null
     */
    public GrayskullClientImpl(GrayskullAuthHeaderProvider authHeaderProvider,
                               GrayskullClientConfiguration grayskullClientConfiguration) {
        if (authHeaderProvider == null) {
            throw new IllegalArgumentException("authHeaderProvider cannot be null");
        }
        if (grayskullClientConfiguration == null) {
            throw new IllegalArgumentException("grayskullClientConfiguration cannot be null");
        }

        // Grayskull-Workload: identity from resolver (once).
        String identity = grayskullClientConfiguration.getWorkloadIdentityResolver().resolve();
        grayskullClientConfiguration.addDefaultHeader(GrayskullHeaders.WORKLOAD, identity);

        // User-Agent: SDK product/version only.
        String sdkVersion = resolveSdkVersion(GrayskullClientImpl.class.getClassLoader());
        String userAgent = "grayskull-java/" + sdkVersion;
        grayskullClientConfiguration.addDefaultHeader(GrayskullHeaders.USER_AGENT, userAgent);

        this.baseUrl = grayskullClientConfiguration.getHost();
        this.httpClient = new GrayskullHttpClient(authHeaderProvider, grayskullClientConfiguration);

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new ParameterNamesModule());

        // Configure metrics based on client configuration
        MetricsPublisher.configure(grayskullClientConfiguration.isMetricsEnabled());

        this.refreshPoller = new HookRefreshPoller(
                httpClient, objectMapper, baseUrl,
                grayskullClientConfiguration.getPollingIntervalSeconds());
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
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            MetricsPublisher.getInstance().recordRequest("getSecret." + secretRef, statusCode, durationMs);
            MDC.remove(MDCKeys.PROJECT_ID);
            MDC.remove(MDCKeys.SECRET_NAME);
            MDC.remove(MDCKeys.GRAYSKULL_REQUEST_ID);
        }
    }

    /**
     * Registers a refresh hook for a secret.
     * <p>
     * The hook is invoked by a background dispatcher whenever the server reports a
     * newer version of {@code secretRef} during the periodic batch poll. Multiple
     * hooks may be registered for the same secret; each is delivered sequentially
     * with the latest known value.
     * </p>
     *
     * @param secretRef the secret reference to monitor, in {@code projectId:secretName} form
     * @param hook the hook to invoke when a newer version of the secret is observed
     * @return a handle that can be used to unregister the hook when no longer needed
     * @throws IllegalStateException if the client has already been closed
     */
    @Override
    public RefreshHandlerRef registerRefreshHook(String secretRef, SecretRefreshHook hook) {
        if (closed.get()) {
            throw new IllegalStateException("GrayskullClient has been closed; cannot register new refresh hooks");
        }
        if (secretRef == null || secretRef.isEmpty()) {
            throw new IllegalArgumentException("secretRef cannot be null or empty");
        }
        if (hook == null) {
            throw new IllegalArgumentException("hook cannot be null");
        }
        String[] parts = parseSecretRef(secretRef);
        return refreshPoller.register(parts[0], parts[1], hook);
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
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        log.info("Closing Grayskull client");
        refreshPoller.close();
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

    private String generateRequestId() {
        return UUID.randomUUID().toString();
    }

    private static String[] parseSecretRef(String secretRef) {
        String[] parts = secretRef.split(":", 2);
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid secretRef format. Expected 'projectId:secretName', got: " + secretRef);
        }
        return parts;
    }

    /**
     * Reads {@code grayskull-client.properties} from the given class loader (Maven-filtered at build time).
     */
    static String resolveSdkVersion(ClassLoader classLoader) {
        try (InputStream in = classLoader.getResourceAsStream("grayskull-client.properties")) {
            if (in == null) {
                return "unknown";
            }
            Properties props = new Properties();
            props.load(in);
            String v = props.getProperty("version");
            if (v != null && !v.trim().isEmpty() && !v.trim().startsWith("${")) {
                return v.trim();
            }
        } catch (IOException e) {
            log.warn("Could not read SDK version from classpath; using 'unknown'.", e);
        }
        return "unknown";
    }
}
