package com.flipkart.grayskull;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.auth.GrayskullAuthHeaderProvider;
import com.flipkart.grayskull.models.GrayskullProperties;
import com.flipkart.grayskull.models.exceptions.GrayskullException;
import com.flipkart.grayskull.models.exceptions.RetryableException;
import com.flipkart.grayskull.metrics.MetricsPublisher;
import com.flipkart.grayskull.models.response.Response;
import com.flipkart.grayskull.models.response.ResponseWithStatus;
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

    GrayskullHttpClient(GrayskullAuthHeaderProvider authHeaderProvider, GrayskullProperties properties) {
        this(authHeaderProvider, properties, true);
    }

    GrayskullHttpClient(GrayskullAuthHeaderProvider authHeaderProvider, GrayskullProperties properties, boolean enableMetrics) {
        this.authHeaderProvider = authHeaderProvider;
        this.objectMapper = new ObjectMapper();
        
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(properties.getConnectionTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(properties.getReadTimeout(), TimeUnit.MILLISECONDS)
                .connectionPool(new ConnectionPool(
                        properties.getMaxConnections(),
                        5,
                        TimeUnit.MINUTES))
                .build();
        
        // Initialize metrics publisher if enabled
        this.metricsPublisher = enableMetrics ? new MetricsPublisher() : null;
    }

    <T> T doGet(String url, TypeReference<Response<T>> responseType, String secretRef, String methodName) throws RetryableException {
        Request request = buildRequest(url)
                .get()
                .build();

        // Execute request with timing
        long startTime = System.nanoTime();
        int statusCode = 0;
        try {
            ResponseWithStatus<T> result = executeRequest(request, responseType);
            statusCode = result.getStatusCode();
            Response<T> response = result.getResponse();
            return response.getData();
        } catch (GrayskullException e) {
            statusCode = e.getStatusCode(); 
            throw e;
        } finally {
            // Record metrics 
            if (metricsPublisher != null) {
                long duration = System.nanoTime() - startTime;
                metricsPublisher.recordRequest(methodName, statusCode, duration, secretRef);
            }
        }
    }

    private Request.Builder buildRequest(String url) {
        Request.Builder requestBuilder = new Request.Builder().url(url);

        if (authHeaderProvider != null) {
            String authHeader = authHeaderProvider.getAuthHeader();
            if (authHeader != null) {
                requestBuilder.addHeader("Authorization", authHeader);
            }
        }

        return requestBuilder;
    }

    private <T> ResponseWithStatus<T> executeRequest(Request request, TypeReference<Response<T>> responseType) throws RetryableException {
        try (okhttp3.Response response = httpClient.newCall(request).execute()) {
            int statusCode = response.code();
            
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                
                // Determine if the error is retryable
                if (isRetryableStatusCode(statusCode)) {
                    throw new RetryableException("Request failed with retryable status " + statusCode + ": " + errorBody);
                } else {
                    throw new GrayskullException(statusCode, "Request failed: " + errorBody);
                }
            }

            if (response.body() == null) {
                throw new RetryableException("Empty response body from server");
            }

            String responseBody = response.body().string();
            log.debug("Received response with status: {}", statusCode);

            Response<T> responseTemplate = objectMapper.readValue(responseBody, responseType);
            
            if (responseTemplate == null || responseTemplate.getData() == null) {
                throw new RetryableException("No data in response");
            }

            return new ResponseWithStatus<>(statusCode, responseTemplate);

        } catch (IOException e) {
            // Network/IO errors are generally transient and worth retrying
            throw new RetryableException("Error communicating with Grayskull server: " + e.getMessage(), e);
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
