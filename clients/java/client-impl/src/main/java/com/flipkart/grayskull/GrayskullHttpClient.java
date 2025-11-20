package com.flipkart.grayskull;

import com.flipkart.grayskull.auth.GrayskullAuthHeaderProvider;
import com.flipkart.grayskull.constants.MDCKeys;
import com.flipkart.grayskull.models.GrayskullClientConfiguration;
import com.flipkart.grayskull.models.response.HttpResponse;
import com.flipkart.grayskull.models.exceptions.GrayskullException;
import com.flipkart.grayskull.models.exceptions.RetryableException;
import com.flipkart.grayskull.metrics.MetricsPublisher;
import com.flipkart.grayskull.utils.RetryUtil;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

class GrayskullHttpClient {
    private static final Logger log = LoggerFactory.getLogger(GrayskullHttpClient.class);

    private final OkHttpClient httpClient;
    private final GrayskullAuthHeaderProvider authHeaderProvider;
    private final MetricsPublisher metricsPublisher;
    private final RetryUtil retryUtil;


    GrayskullHttpClient(GrayskullAuthHeaderProvider authHeaderProvider, GrayskullClientConfiguration clientConfiguration) {
        this.authHeaderProvider = authHeaderProvider;
        
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(clientConfiguration.getConnectionTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(clientConfiguration.getReadTimeout(), TimeUnit.MILLISECONDS)
                .connectionPool(new ConnectionPool(
                        clientConfiguration.getMaxConnections(),
                        5,
                        TimeUnit.MINUTES))
                .build();
        
        // Initialize metrics publisher if enabled
        this.metricsPublisher = clientConfiguration.isMetricsEnabled() ? new MetricsPublisher() : null;
        
        // Initialize retry utility
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
            // Configuration or usage errors - rethrow as-is
            throw e;
        
        } catch (GrayskullException e) {
            // GrayskullException already has proper context (retry exhaustion, etc.) - rethrow as-is
            throw e;
            
        } catch (Exception e) {
            throw new GrayskullException(500, "Unexpected error during HTTP request", e);

        } finally {
            if (metricsPublisher != null && attemptCount[0] > 1) {
                metricsPublisher.recordRetry(url, attemptCount[0], finalAttemptSuccess);
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

    private Request.Builder buildRequest(String url) {
        Request.Builder requestBuilder = new Request.Builder().url(url);

        String authHeader = authHeaderProvider.getAuthHeader();
        if (authHeader == null || authHeader.trim().isEmpty()) {
            throw new IllegalStateException("Auth header cannot be null or empty");
        }
        requestBuilder.addHeader("Authorization", authHeader);

        String requestId = MDC.get(MDCKeys.GRAYSKULL_REQUEST_ID);
        if (requestId != null && !requestId.isEmpty()) {
            requestBuilder.addHeader("X-Request-Id", requestId);
        }

        return requestBuilder;
    }

    private HttpResponse executeRequest(Request request) throws RetryableException {
        try (okhttp3.Response response = httpClient.newCall(request).execute()) {
            int statusCode = response.code();
            
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                
                // Determine if the error is retryable
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

        } catch (IOException e) {
            // Network/IO errors (timeouts, connection issues) are generally transient and worth retrying
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
