package com.flipkart.grayskull;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.auth.GrayskullAuthHeaderProvider;
import com.flipkart.grayskull.models.GrayskullClientConfiguration;
import com.flipkart.grayskull.models.response.HttpResponse;
import com.flipkart.grayskull.models.SecretValue;
import com.flipkart.grayskull.models.exceptions.GrayskullException;
import com.flipkart.grayskull.hooks.RefreshHandlerRef;
import com.flipkart.grayskull.hooks.SecretRefreshHook;
import com.flipkart.grayskull.models.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GrayskullClientImpl.
 */
@ExtendWith(MockitoExtension.class)
class GrayskullClientImplTest {

    @Mock
    private GrayskullAuthHeaderProvider mockAuthProvider;

    @Mock
    private GrayskullHttpClient mockHttpClient;

    private GrayskullClientConfiguration grayskullClientConfiguration;
    private GrayskullClientImpl client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        grayskullClientConfiguration = new GrayskullClientConfiguration();
        grayskullClientConfiguration.setHost("https://test.grayskull.com");
        grayskullClientConfiguration.setConnectionTimeout(5000);
        grayskullClientConfiguration.setReadTimeout(10000);
        
        client = new GrayskullClientImpl(mockAuthProvider, grayskullClientConfiguration);
        objectMapper = new ObjectMapper();
        
        // Inject mock HTTP client using reflection
        Field httpClientField = GrayskullClientImpl.class.getDeclaredField("httpClient");
        httpClientField.setAccessible(true);
        httpClientField.set(client, mockHttpClient);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void testConstructor_nullAuthHeaderProvider() {
        // Given
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        config.setHost("https://test.grayskull.com");

        // When/Then
        assertThrows(IllegalArgumentException.class,
                () -> new GrayskullClientImpl(null, config),
                "authHeaderProvider cannot be null");
    }

    @Test
    void testConstructor_nullConfiguration() {
        // When/Then
        assertThrows(IllegalArgumentException.class,
                () -> new GrayskullClientImpl(mockAuthProvider, null),
                "grayskullClientConfiguration cannot be null");
    }

    @Test
    void testConstructor_bothNull() {
        // When/Then
        assertThrows(IllegalArgumentException.class,
                () -> new GrayskullClientImpl(null, null),
                "authHeaderProvider cannot be null");
    }

    @Test
    void testConstructor_authProviderReturnsNull() {
        // Given
        GrayskullAuthHeaderProvider nullAuthProvider = () -> null;
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        config.setHost("https://test.grayskull.com");

        // When - constructor should succeed
        GrayskullClientImpl clientWithNullAuth = new GrayskullClientImpl(nullAuthProvider, config);

        // Then - but getSecret should fail when auth header is needed
        assertThrows(IllegalStateException.class,
                () -> clientWithNullAuth.getSecret("project:secret"),
                "Auth header cannot be null or empty");
        
        clientWithNullAuth.close();
    }

    @Test
    void testConstructor_authProviderReturnsEmpty() {
        // Given
        GrayskullAuthHeaderProvider emptyAuthProvider = () -> "";
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        config.setHost("https://test.grayskull.com");

        // When - constructor should succeed
        GrayskullClientImpl clientWithEmptyAuth = new GrayskullClientImpl(emptyAuthProvider, config);

        // Then - but getSecret should fail when auth header is needed
        assertThrows(IllegalStateException.class,
                () -> clientWithEmptyAuth.getSecret("project:secret"),
                "Auth header cannot be null or empty");
        
        clientWithEmptyAuth.close();
    }

    @Test
    void testConstructor_authProviderReturnsWhitespace() {
        // Given
        GrayskullAuthHeaderProvider whitespaceAuthProvider = () -> "   ";
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        config.setHost("https://test.grayskull.com");

        // When - constructor should succeed
        GrayskullClientImpl clientWithWhitespaceAuth = new GrayskullClientImpl(whitespaceAuthProvider, config);

        // Then - but getSecret should fail when auth header is whitespace-only
        assertThrows(IllegalStateException.class,
                () -> clientWithWhitespaceAuth.getSecret("project:secret"),
                "Auth header cannot be null or empty");
        
        clientWithWhitespaceAuth.close();
    }

    @Test
    void testGetSecret_success() throws Exception {
        // Given
        String secretRef = "my-project:database-password";
        SecretValue expectedSecret = new SecretValue(1, "username", "password123");
        HttpResponse httpResponse = createHttpResponse(expectedSecret);

        when(mockHttpClient.doGetWithRetry(anyString()))
                .thenReturn(httpResponse);

        // When
        SecretValue result = client.getSecret(secretRef);

        // Then
        assertNotNull(result);
        assertEquals(expectedSecret.getDataVersion(), result.getDataVersion());
        assertEquals(expectedSecret.getPublicPart(), result.getPublicPart());
        assertEquals(expectedSecret.getPrivatePart(), result.getPrivatePart());
        
        // Verify correct URL was called
        verify(mockHttpClient).doGetWithRetry(
                eq("https://test.grayskull.com/v1/projects/my-project/secrets/database-password/data")
        );
    }

    @Test
    void testGetSecret_nullSecretRef() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> client.getSecret(null));
    }

    @Test
    void testGetSecret_emptySecretRef() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> client.getSecret(""));
    }

    @Test
    void testGetSecret_invalidFormat_noColon() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> client.getSecret("invalid-format"));
    }

    @Test
    void testGetSecret_invalidFormat_emptyProjectId() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> client.getSecret(":secret-name"));
    }

    @Test
    void testGetSecret_invalidFormat_emptySecretName() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> client.getSecret("project-id:"));
    }

    @Test
    void testGetSecret_validFormat_withMultipleColons() throws Exception {
        // Given
        String secretRef = "my-project:secret:with:colons";
        SecretValue expectedSecret = new SecretValue(1, "pub", "priv");
        HttpResponse httpResponse = createHttpResponse(expectedSecret);

        when(mockHttpClient.doGetWithRetry(anyString()))
                .thenReturn(httpResponse);

        // When
        SecretValue result = client.getSecret(secretRef);

        // Then
        assertNotNull(result);
        
        // Verify URL is properly encoded - colons are allowed unencoded per RFC 3986
        verify(mockHttpClient).doGetWithRetry(
                eq("https://test.grayskull.com/v1/projects/my-project/secrets/secret:with:colons/data")
        );
    }

    @Test
    void testGetSecret_withSpecialCharacters() throws Exception {
        // Given - secret name contains @ and # characters
        String secretRef = "project:secret@domain#tag";
        SecretValue expectedSecret = new SecretValue(1, "username", "password");
        HttpResponse httpResponse = createHttpResponse(expectedSecret);

        when(mockHttpClient.doGetWithRetry(anyString()))
                .thenReturn(httpResponse);

        // When
        SecretValue result = client.getSecret(secretRef);

        // Then
        assertNotNull(result);
        assertEquals(expectedSecret.getDataVersion(), result.getDataVersion());
        assertEquals(expectedSecret.getPublicPart(), result.getPublicPart());
        assertEquals(expectedSecret.getPrivatePart(), result.getPrivatePart());
        
        // Verify URL is properly encoded with special characters per RFC 3986
        // @ is allowed unencoded, # is encoded as %23
        verify(mockHttpClient).doGetWithRetry(
                eq("https://test.grayskull.com/v1/projects/project/secrets/secret@domain%23tag/data")
        );
    }

    @Test
    void testGetSecret_nullResponse() throws Exception {
        // Given
        String secretRef = "project:secret";
        Response<SecretValue> response = new Response<>(null, "No Data");
        String json = objectMapper.writeValueAsString(response);
        HttpResponse httpResponse = new HttpResponse(200, json, "application/json", "http/1.1");
        
        when(mockHttpClient.doGetWithRetry(anyString()))
                .thenReturn(httpResponse);

        // When/Then
        assertThrows(GrayskullException.class, () -> client.getSecret(secretRef));
    }

    @Test
    void testRegisterRefreshHook_success() {
        // Given
        String secretRef = "secengg-stage:secret-1";
        AtomicInteger callCount = new AtomicInteger(0);
        
        SecretRefreshHook hook = (secretVal) -> {
            callCount.incrementAndGet();
            System.out.println("Secret refreshed: " + secretVal);
        };

        // When
        RefreshHandlerRef handle = client.registerRefreshHook(secretRef, hook);

        // Then
        assertNotNull(handle);
        // No-op implementation returns empty string and is always inactive
        assertEquals("", handle.getSecretRef());
        assertFalse(handle.isActive());
        
        // Verify hook was never called (placeholder implementation)
        assertEquals(0, callCount.get());
    }

    @Test
    void testRegisterRefreshHook_canUnregister() {
        // Given
        String secretRef = "secengg-stage:secret-1";
        SecretRefreshHook hook = (secretVal) -> System.out.println("test");

        // When
        RefreshHandlerRef handle = client.registerRefreshHook(secretRef, hook);
        
        // unRegister() is a no-op but shouldn't throw
        handle.unRegister();
    }

    @Test
    void testRegisterRefreshHook_nullSecretRef() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> client.registerRefreshHook(null, (secretVal) -> {}));
    }

    @Test
    void testRegisterRefreshHook_emptySecretRef() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> client.registerRefreshHook("", (secretVal) -> {}));
    }

    @Test
    void testRegisterRefreshHook_nullHook() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> client.registerRefreshHook("project:secret", null));
    }

    @Test
    void testClose_cleansUpResources() {
        // When
        client.close();

        // Then
        verify(mockHttpClient).close();
    }

    @Test
    void testClose_handlesNullHttpClient() throws Exception {
        // Given - create client with null http client
        GrayskullClientImpl clientWithNullHttp = new GrayskullClientImpl(mockAuthProvider, grayskullClientConfiguration);
        Field httpClientField = GrayskullClientImpl.class.getDeclaredField("httpClient");
        httpClientField.setAccessible(true);
        httpClientField.set(clientWithNullHttp, null);

        // When/Then - should not throw exception
        clientWithNullHttp.close();
    }
    
    private HttpResponse createHttpResponse(SecretValue secretValue) throws Exception {
        Response<SecretValue> response = new Response<>(secretValue, "Success");
        String json = objectMapper.writeValueAsString(response);
        return new HttpResponse(200, json, "application/json", "http/1.1");
    }
}
