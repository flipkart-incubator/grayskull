package com.flipkart.grayskull;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.auth.GrayskullAuthHeaderProvider;
import com.flipkart.grayskull.exceptions.GrayskullException;
import com.flipkart.grayskull.models.GrayskullProperties;
import com.flipkart.grayskull.models.response.Response;
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

    GrayskullHttpClient(GrayskullAuthHeaderProvider authHeaderProvider, GrayskullProperties properties) {
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
    }

    <T> T doGet(String url, TypeReference<Response<T>> responseType) {
        Request request = buildRequest(url)
                .get()
                .build();

        return executeRequest(request, responseType);
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

    private <T> T executeRequest(Request request, TypeReference<Response<T>> responseType) {
        try (okhttp3.Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                throw new GrayskullException(
                        String.format("Request failed. HTTP %d: %s", response.code(), errorBody));
            }

            if (response.body() == null) {
                throw new GrayskullException("Empty response body from server");
            }

            String responseBody = response.body().string();
            log.debug("Received response: {}", responseBody);

            Response<T> responseTemplate = objectMapper.readValue(responseBody, responseType);
            
            if (responseTemplate == null || responseTemplate.getData() == null) {
                throw new GrayskullException("No data in response");
            }

            return responseTemplate.getData();

        } catch (IOException e) {
            throw new GrayskullException("Error communicating with Grayskull server", e);
        }
    }

    void close() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}
