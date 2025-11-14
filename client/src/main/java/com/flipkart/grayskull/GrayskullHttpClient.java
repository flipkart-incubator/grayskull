package com.flipkart.grayskull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.flipkart.grayskull.auth.GrayskullAuthHeaderProvider;
import com.flipkart.grayskull.models.GrayskullClientConfiguration;
import com.flipkart.grayskull.models.exceptions.GrayskullException;
import com.flipkart.grayskull.models.exceptions.RetryableException;
import com.flipkart.grayskull.metrics.MetricsPublisher;
import com.flipkart.grayskull.models.response.Response;
import com.flipkart.grayskull.models.response.ResponseWithStatus;
import com.flipkart.grayskull.utils.RetryUtil;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

class GrayskullHttpClient {
    private static final Logger log = LoggerFactory.getLogger(GrayskullHttpClient.class);

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final GrayskullAuthHeaderProvider authHeaderProvider;
    private final MetricsPublisher metricsPublisher;
    private final RetryUtil retryUtil;


    GrayskullHttpClient(GrayskullAuthHeaderProvider authHeaderProvider, GrayskullClientConfiguration clientConfiguration) {
        this.authHeaderProvider = authHeaderProvider;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new ParameterNamesModule());
        
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

    <T> T doGetWithRetry(String url, TypeReference<Response<T>> responseType) {
        
        long startTime = System.nanoTime();
        final int[] attemptCount = {0};
        final int[] lastStatusCode = {0};
        boolean success = false;
        
        try {
            T result = retryUtil.retry(() -> {
                attemptCount[0]++;
                ResponseWithStatus<T> responseWithStatus = doGet(url, responseType);
                lastStatusCode[0] = responseWithStatus.getStatusCode();
                return responseWithStatus.getResponse().getData();
            });
            
            success = true;
            return result;
            
        } catch (IllegalStateException | IllegalArgumentException e) {
            // Configuration or usage errors - rethrow as-is
            throw e;

        } catch (GrayskullException e) {
            // Capture status code (from non-retryable errors or exhausted retries)
            lastStatusCode[0] = e.getStatusCode();
            throw e;

        } catch (Exception e) {
            throw new GrayskullException("Unexpected error during HTTP request", e);

        } finally {
            // Record metrics for the entire operation (success or failure)
            if (metricsPublisher != null) {
                long duration = System.nanoTime() - startTime;
                long durationMs = TimeUnit.NANOSECONDS.toMillis(duration);
                metricsPublisher.recordRequest(url, lastStatusCode[0], durationMs);
                
                // If we had to retry (more than 1 attempt), record retry metric
                if (attemptCount[0] > 1) {
                    metricsPublisher.recordRetry(url, attemptCount[0], success);
                }
            }
        }
    }

    <T> ResponseWithStatus<T> doGet(String url, TypeReference<Response<T>> responseType) throws RetryableException {
        Request request = buildRequest(url)
                .get()
                .build();

        return executeRequest(request, responseType);
    }

    private Request.Builder buildRequest(String url) {
        Request.Builder requestBuilder = new Request.Builder().url(url);

        String authHeader = authHeaderProvider.getAuthHeader();
        if (authHeader == null || authHeader.trim().isEmpty()) {
            throw new IllegalStateException("Auth header cannot be null or empty");
        }
        requestBuilder.addHeader("Authorization", authHeader);

        return requestBuilder;
    }

    private <T> ResponseWithStatus<T> executeRequest(Request request, TypeReference<Response<T>> responseType) throws RetryableException {
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

            if (response.body() == null) {
                throw new GrayskullException("Empty response body from server");
            }

            String responseBody = response.body().string();
            log.debug("Received response from {} with status: {}", request.url(), statusCode);

            try {
                Response<T> responseTemplate = objectMapper.readValue(responseBody, responseType);
                return new ResponseWithStatus<>(statusCode, responseTemplate);
            } catch (JsonProcessingException e) {
                // JSON parsing errors are not retryable - they indicate a permanent problem
                throw new GrayskullException("Failed to parse response: " + e.getMessage(), e);
            }

        } catch (IOException e) {
            // Network/IO errors (timeouts, connection issues) are generally transient and worth retrying
            throw new RetryableException(e.getMessage(), e);
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
