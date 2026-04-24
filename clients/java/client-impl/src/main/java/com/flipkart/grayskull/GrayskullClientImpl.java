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
 * Implementation of the Grayskull client. Fetches secrets on demand and delegates
 * refresh-hook registration and background polling to {@link HookRefreshPoller}.
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

    public GrayskullClientImpl(GrayskullAuthHeaderProvider authHeaderProvider,
                               GrayskullClientConfiguration grayskullClientConfiguration) {
        if (authHeaderProvider == null) {
            throw new IllegalArgumentException("authHeaderProvider cannot be null");
        }
        if (grayskullClientConfiguration == null) {
            throw new IllegalArgumentException("grayskullClientConfiguration cannot be null");
        }

        // Resolve workload identity once and pin it as a default header.
        String identity = grayskullClientConfiguration.getWorkloadIdentityResolver().resolve();
        grayskullClientConfiguration.addDefaultHeader(GrayskullHeaders.WORKLOAD, identity);

        String sdkVersion = resolveSdkVersion(GrayskullClientImpl.class.getClassLoader());
        String userAgent = "grayskull-java/" + sdkVersion + " (" + identity + ")";
        grayskullClientConfiguration.addDefaultHeader(GrayskullHeaders.USER_AGENT, userAgent);

        this.baseUrl = grayskullClientConfiguration.getHost();
        this.httpClient = new GrayskullHttpClient(authHeaderProvider, grayskullClientConfiguration);

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new ParameterNamesModule());

        MetricsPublisher.configure(grayskullClientConfiguration.isMetricsEnabled());

        this.refreshPoller = new HookRefreshPoller(
                httpClient, objectMapper, baseUrl,
                grayskullClientConfiguration.getPollingIntervalSeconds());
    }

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

            log.debug("Fetching secret for secretRef:{}", secretRef);

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

    @Override
    public RefreshHandlerRef registerRefreshHook(String secretRef, SecretRefreshHook hook) {
        if (secretRef == null || secretRef.isEmpty()) {
            throw new IllegalArgumentException("secretRef cannot be null or empty");
        }
        if (hook == null) {
            throw new IllegalArgumentException("hook cannot be null");
        }
        String[] parts = parseSecretRef(secretRef);
        return refreshPoller.register(parts[0], parts[1], hook);
    }

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
