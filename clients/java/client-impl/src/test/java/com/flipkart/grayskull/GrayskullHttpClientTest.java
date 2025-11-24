package com.flipkart.grayskull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.auth.GrayskullAuthHeaderProvider;
import com.flipkart.grayskull.models.GrayskullClientConfiguration;
import com.flipkart.grayskull.models.response.HttpResponse;
import com.flipkart.grayskull.models.SecretValue;
import com.flipkart.grayskull.models.exceptions.GrayskullException;
import com.flipkart.grayskull.models.response.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for GrayskullHttpClient.
 * <p>
 * Uses MockWebServer for integration-style testing of HTTP behavior.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class GrayskullHttpClientTest {

    @Mock
    private GrayskullAuthHeaderProvider mockAuthProvider;

    private MockWebServer mockWebServer;
    private GrayskullHttpClient httpClient;
    private GrayskullClientConfiguration config;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        config = new GrayskullClientConfiguration();
        config.setHost(mockWebServer.url("/").toString().replaceAll("/$", ""));
        config.setConnectionTimeout(5000);
        config.setReadTimeout(5000);
        config.setMaxRetries(3);
        config.setMinRetryDelay(50); // Short delay for faster tests
        config.setMaxConnections(10);
        config.setMetricsEnabled(false);

        lenient().when(mockAuthProvider.getAuthHeader()).thenReturn("Bearer test-token");

        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (httpClient != null) {
            httpClient.close();
        }
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    @Test
    void testDoGetWithRetry_success() {
        // Given
        httpClient = new GrayskullHttpClient(mockAuthProvider, config);
        SecretValue expectedSecret = new SecretValue(1, "public-value", "private-value");
        Response<SecretValue> response = new Response<>(expectedSecret, "Success");
        String jsonResponse = toJson(response);

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(jsonResponse)
                .addHeader("Content-Type", "application/json"));

        // When
        HttpResponse result = httpClient.doGetWithRetry(
                mockWebServer.url("/test").toString()
        );

        // Then
        assertNotNull(result);
        assertEquals(200, result.getStatusCode());
        assertEquals(jsonResponse, result.getBody());
    }

    @Test
    void testDoGetWithRetry_includesAuthHeader() throws InterruptedException {
        // Given
        httpClient = new GrayskullHttpClient(mockAuthProvider, config);
        Response<SecretValue> response = new Response<>(new SecretValue(1, "pub", "priv"), "Success");

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(toJson(response))
                .addHeader("Content-Type", "application/json"));

        // When
        httpClient.doGetWithRetry(
                mockWebServer.url("/test").toString()
        );

        // Then
        RecordedRequest request = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("Bearer test-token", request.getHeader("Authorization"));
    }

    @Test
    void testDoGetWithRetry_nullAuthHeader_throwsException() {
        // Given
        when(mockAuthProvider.getAuthHeader()).thenReturn(null);
        httpClient = new GrayskullHttpClient(mockAuthProvider, config);

        // When/Then
        assertThrows(IllegalStateException.class, () ->
                httpClient.doGetWithRetry(
                        mockWebServer.url("/test").toString()
                ),
                "Auth header cannot be null or empty"
        );
    }

    @Test
    void testDoGetWithRetry_emptyAuthHeader_throwsException() {
        // Given
        when(mockAuthProvider.getAuthHeader()).thenReturn("   ");
        httpClient = new GrayskullHttpClient(mockAuthProvider, config);

        // When/Then
        assertThrows(IllegalStateException.class, () ->
                httpClient.doGetWithRetry(
                        mockWebServer.url("/test").toString()
                )
        );
    }


    @Test
    void testDoGetWithRetry_retriesOnRetryableException() throws InterruptedException {
        // Given
        httpClient = new GrayskullHttpClient(mockAuthProvider, config);
        SecretValue expectedSecret = new SecretValue(1, "pub", "priv");
        Response<SecretValue> response = new Response<>(expectedSecret, "Success");
        String jsonResponse = toJson(response);

        // Mock to fail twice with 500, then succeed
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(503).setBody("Service Unavailable"));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(jsonResponse)
                .addHeader("Content-Type", "application/json"));

        // When
        HttpResponse result = httpClient.doGetWithRetry(
                mockWebServer.url("/test").toString()
        );

        // Then - should succeed after retries
        assertNotNull(result);
        assertEquals(200, result.getStatusCode());
        assertEquals(jsonResponse, result.getBody());
        assertEquals(3, mockWebServer.getRequestCount(), "Should have made 3 requests (2 failures + 1 success)");
    }

    @Test
    void testDoGetWithRetry_retriesOnNetworkError() throws InterruptedException {
        // Given
        httpClient = new GrayskullHttpClient(mockAuthProvider, config);
        SecretValue expectedSecret = new SecretValue(1, "pub", "priv");
        Response<SecretValue> response = new Response<>(expectedSecret, "Success");
        String jsonResponse = toJson(response);

        // Mock to fail with connection reset, then succeed
        mockWebServer.enqueue(new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(jsonResponse)
                .addHeader("Content-Type", "application/json"));

        // When
        HttpResponse result = httpClient.doGetWithRetry(
                mockWebServer.url("/test").toString()
        );

        // Then - should succeed after retry
        assertNotNull(result);
        assertEquals(200, result.getStatusCode());
        assertEquals(jsonResponse, result.getBody());
        assertEquals(2, mockWebServer.getRequestCount(), "Should have made 2 requests");
    }

    @Test
    void testDoGetWithRetry_retriesOn429RateLimiting() throws InterruptedException {
        // Given
        httpClient = new GrayskullHttpClient(mockAuthProvider, config);
        SecretValue expectedSecret = new SecretValue(1, "pub", "priv");
        Response<SecretValue> response = new Response<>(expectedSecret, "Success");
        String jsonResponse = toJson(response);

        // Mock to fail with rate limiting, then succeed
        mockWebServer.enqueue(new MockResponse().setResponseCode(429).setBody("Too Many Requests"));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(jsonResponse)
                .addHeader("Content-Type", "application/json"));

        // When
        HttpResponse result = httpClient.doGetWithRetry(
                mockWebServer.url("/test").toString()
        );

        // Then
        assertNotNull(result);
        assertEquals(200, result.getStatusCode());
        assertEquals(jsonResponse, result.getBody());
        assertEquals(2, mockWebServer.getRequestCount());
    }

    @Test
    void testDoGetWithRetry_exhaustsRetries_throwsGrayskullException() {
        // Given
        httpClient = new GrayskullHttpClient(mockAuthProvider, config);

        // Mock to always fail with 500
        for (int i = 0; i < config.getMaxRetries(); i++) {
            mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));
        }

        // When/Then
        GrayskullException exception = assertThrows(GrayskullException.class, () ->
                httpClient.doGetWithRetry(
                        mockWebServer.url("/test").toString()
                )
        );

        assertTrue(exception.getMessage().contains("Failed after"), 
                "Exception should indicate retry exhaustion");
        assertEquals(config.getMaxRetries(), mockWebServer.getRequestCount(), 
                "Should have made exactly maxRetries requests");
    }

    @Test
    void testDoGetWithRetry_noRetryOnNonRetryableException() {
        // Given
        httpClient = new GrayskullHttpClient(mockAuthProvider, config);

        // Mock 404 - non-retryable
        mockWebServer.enqueue(new MockResponse().setResponseCode(404).setBody("Not Found"));

        // When/Then
        GrayskullException exception = assertThrows(GrayskullException.class, () ->
                httpClient.doGetWithRetry(
                        mockWebServer.url("/test").toString()
                )
        );

        assertEquals(404, exception.getStatusCode());
        assertEquals(1, mockWebServer.getRequestCount(), "Should only make 1 request for non-retryable error");
    }

    @Test
    void testDoGetWithRetry_400BadRequest_noRetry() {
        // Given
        httpClient = new GrayskullHttpClient(mockAuthProvider, config);

        mockWebServer.enqueue(new MockResponse().setResponseCode(400).setBody("Bad Request"));

        // When/Then
        GrayskullException exception = assertThrows(GrayskullException.class, () ->
                httpClient.doGetWithRetry(
                        mockWebServer.url("/test").toString()
                )
        );

        assertEquals(400, exception.getStatusCode());
        assertEquals(1, mockWebServer.getRequestCount());
    }

    @Test
    void testDoGetWithRetry_401Unauthorized_noRetry() {
        // Given
        httpClient = new GrayskullHttpClient(mockAuthProvider, config);

        mockWebServer.enqueue(new MockResponse().setResponseCode(401).setBody("Unauthorized"));

        // When/Then
        GrayskullException exception = assertThrows(GrayskullException.class, () ->
                httpClient.doGetWithRetry(
                        mockWebServer.url("/test").toString()
                )
        );

        assertEquals(401, exception.getStatusCode());
        assertEquals(1, mockWebServer.getRequestCount());
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }
}
