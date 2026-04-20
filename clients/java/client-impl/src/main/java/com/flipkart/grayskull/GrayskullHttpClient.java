package com.flipkart.grayskull;

import com.flipkart.grayskull.auth.GrayskullAuthHeaderProvider;
import com.flipkart.grayskull.constants.MDCKeys;
import com.flipkart.grayskull.models.GrayskullClientConfiguration;
import com.flipkart.grayskull.models.response.HttpResponse;
import com.flipkart.grayskull.models.exceptions.GrayskullException;
import com.flipkart.grayskull.models.exceptions.RetryableException;
import com.flipkart.grayskull.metrics.MetricsPublisher;
import com.flipkart.grayskull.spi.DefaultHostIdentityProvider;
import com.flipkart.grayskull.spi.HostIdentityProvider;
import com.flipkart.grayskull.spi.SdkVersion;
import com.flipkart.grayskull.utils.RetryUtil;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

/**
 * HTTP transport for the Grayskull client.
 * <p>
 * This class is intentionally kept free of any Kubernetes-specific coupling;
 * host identity (what Flipkart's deployments surface as the K8s SA token) is
 * injected via a pluggable {@link HostIdentityProvider} so the OSS artifact
 * stays environment-agnostic.
 * <p>
 * <b>Headers added on every outbound request</b> (today only the two data-plane
 * endpoints — {@code GET /secrets/{name}/data} and {@code POST /secrets/batch}
 * — invoke this client, so the headers are scoped to those calls by virtue of
 * the call surface rather than by an annotation/AOP layer):
 * <ul>
 *   <li>{@code Authorization} — from {@link GrayskullAuthHeaderProvider}.</li>
 *   <li>{@code X-Request-Id} — from MDC, propagated for correlation.</li>
 *   <li>{@code X-Grayskull-SDK-Version} — resolved once from a Maven-filtered
 *       resource via {@link SdkVersion}.</li>
 *   <li>{@code X-Grayskull-Host-Identification} — from the injected
 *       {@link HostIdentityProvider}; omitted when the provider returns blank.</li>
 * </ul>
 */
class GrayskullHttpClient {
    private static final Logger log = LoggerFactory.getLogger(GrayskullHttpClient.class);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    static final String HEADER_AUTHORIZATION = "Authorization";
    static final String HEADER_REQUEST_ID = "X-Request-Id";
    static final String HEADER_SDK_VERSION = "X-Grayskull-SDK-Version";
    static final String HEADER_HOST_IDENTIFICATION = "X-Grayskull-Host-Identification";

    private final OkHttpClient httpClient;
    private final GrayskullAuthHeaderProvider authHeaderProvider;
    private final HostIdentityProvider hostIdentityProvider;
    private final RetryUtil retryUtil;
    private final String sdkVersion;

    GrayskullHttpClient(GrayskullAuthHeaderProvider authHeaderProvider,
                        GrayskullClientConfiguration clientConfiguration) {
        this(authHeaderProvider, clientConfiguration, new DefaultHostIdentityProvider());
    }

    GrayskullHttpClient(GrayskullAuthHeaderProvider authHeaderProvider,
                        GrayskullClientConfiguration clientConfiguration,
                        HostIdentityProvider hostIdentityProvider) {
        if (hostIdentityProvider == null) {
            throw new IllegalArgumentException("hostIdentityProvider cannot be null");
        }
        this.authHeaderProvider = authHeaderProvider;
        this.hostIdentityProvider = hostIdentityProvider;
        this.sdkVersion = SdkVersion.get();

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(clientConfiguration.getConnectionTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(clientConfiguration.getReadTimeout(), TimeUnit.MILLISECONDS)
                .connectionPool(new ConnectionPool(
                        clientConfiguration.getMaxConnections(),
                        5,
                        TimeUnit.MINUTES))
                .build();

        this.retryUtil = new RetryUtil(clientConfiguration.getMaxRetries(), clientConfiguration.getMinRetryDelay());
    }

    HttpResponse doGetWithRetry(String url) {
        final int[] attemptCount = {0};
        boolean finalAttemptSuccess = false;

        try {
            HttpResponse result = retryUtil.retry(() -> {
                attemptCount[0]++;
                return doGet(url);
            });

            finalAttemptSuccess = true;
            return result;

        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (GrayskullException e) {
            throw e;
        } catch (Exception e) {
            throw new GrayskullException(500, "Unexpected error during HTTP request", e);
        } finally {
            if (attemptCount[0] > 1) {
                MetricsPublisher.getInstance().recordRetry(url, attemptCount[0], finalAttemptSuccess);
            }
        }
    }

    HttpResponse doGet(String url) throws RetryableException {
        Request request = buildRequest(url)
                .get()
                .build();

        String requestId = MDC.get(MDCKeys.GRAYSKULL_REQUEST_ID);
        log.debug("[RequestId:{}] Executing GET request to: {}", requestId, url);
        HttpResponse httpResponse = executeRequest(request);

        String body = httpResponse.getBody();
        int bodyLength = body != null ? body.length() : 0;
        log.debug("[RequestId:{}] Received response from {} with status: {}, protocol: {}, contentType: {}, bodyLength: {} bytes",
                requestId, url, httpResponse.getStatusCode(), httpResponse.getProtocol(), httpResponse.getContentType(), bodyLength);

        return httpResponse;
    }

    HttpResponse doPostWithRetry(String url, String jsonBody) {
        final int[] attemptCount = {0};
        boolean finalAttemptSuccess = false;

        try {
            HttpResponse result = retryUtil.retry(() -> {
                attemptCount[0]++;
                return doPost(url, jsonBody);
            });

            finalAttemptSuccess = true;
            return result;

        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (GrayskullException e) {
            throw e;
        } catch (Exception e) {
            throw new GrayskullException(500, "Unexpected error during HTTP request", e);
        } finally {
            if (attemptCount[0] > 1) {
                MetricsPublisher.getInstance().recordRetry(url, attemptCount[0], finalAttemptSuccess);
            }
        }
    }

    HttpResponse doPost(String url, String jsonBody) throws RetryableException {
        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
        Request request = buildRequest(url)
                .post(body)
                .build();

        String requestId = MDC.get(MDCKeys.GRAYSKULL_REQUEST_ID);
        log.debug("[RequestId:{}] Executing POST request to: {}", requestId, url);
        HttpResponse httpResponse = executeRequest(request);

        String responseBody = httpResponse.getBody();
        int bodyLength = responseBody != null ? responseBody.length() : 0;
        log.debug("[RequestId:{}] Received POST response from {} with status: {}, bodyLength: {} bytes",
                requestId, url, httpResponse.getStatusCode(), bodyLength);

        return httpResponse;
    }

    private Request.Builder buildRequest(String url) {
        Request.Builder requestBuilder = new Request.Builder().url(url);

        String authHeader = authHeaderProvider.getAuthHeader();
        if (authHeader == null || authHeader.trim().isEmpty()) {
            throw new IllegalStateException("Auth header cannot be null or empty");
        }
        requestBuilder.addHeader(HEADER_AUTHORIZATION, authHeader);

        String requestId = MDC.get(MDCKeys.GRAYSKULL_REQUEST_ID);
        if (requestId != null && !requestId.isEmpty()) {
            requestBuilder.addHeader(HEADER_REQUEST_ID, requestId);
        }

        requestBuilder.addHeader(HEADER_SDK_VERSION, sdkVersion);

        String hostId = hostIdentityProvider.getHostIdentification();
        // Defensive sanitisation: strip CR/LF to prevent HTTP header injection
        // even though the SPI contract forbids them. Empty => omit the header
        // (per contract) so the server can distinguish "no identity" from a
        // literal empty string.
        if (hostId != null && !hostId.isEmpty()) {
            String sanitised = hostId.replace('\r', ' ').replace('\n', ' ');
            requestBuilder.addHeader(HEADER_HOST_IDENTIFICATION, sanitised);
        }

        return requestBuilder;
    }

    private HttpResponse executeRequest(Request request) throws RetryableException {
        try (okhttp3.Response response = httpClient.newCall(request).execute()) {
            int statusCode = response.code();

            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";

                if (isRetryableStatusCode(statusCode)) {
                    throw new RetryableException(statusCode, "Request failed: " + errorBody);
                } else {
                    throw new GrayskullException(statusCode, "Request failed: " + errorBody);
                }
            }

            String responseBody = response.body() != null ? response.body().string() : null;
            String contentType = response.header("Content-Type", "unknown");
            String protocol = response.protocol().toString();
            return new HttpResponse(statusCode, responseBody, contentType, protocol);

        } catch (SocketTimeoutException e) {
            throw new RetryableException(500, "Timeout while communicating with Grayskull server", e);

        } catch (IOException e) {
            throw new RetryableException(500, "Error communicating with Grayskull server", e);
        }
    }

    private boolean isRetryableStatusCode(int statusCode) {
        return statusCode == 429 || (statusCode >= 500 && statusCode < 600);
    }

    void close() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}
