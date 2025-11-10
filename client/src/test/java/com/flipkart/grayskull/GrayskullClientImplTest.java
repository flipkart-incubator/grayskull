package com.flipkart.grayskull;

import com.fasterxml.jackson.core.type.TypeReference;
import com.flipkart.grayskull.auth.GrayskullAuthHeaderProvider;
import com.flipkart.grayskull.exceptions.GrayskullException;
import com.flipkart.grayskull.hooks.RefreshHookHandle;
import com.flipkart.grayskull.hooks.SecretRefreshHook;
import com.flipkart.grayskull.models.GrayskullProperties;
import com.flipkart.grayskull.models.SecretValue;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GrayskullClientImpl.
 */
@RunWith(MockitoJUnitRunner.class)
public class GrayskullClientImplTest {

    @Mock
    private GrayskullAuthHeaderProvider mockAuthProvider;

    @Mock
    private GrayskullHttpClient mockHttpClient;

    private GrayskullProperties properties;
    private GrayskullClientImpl client;

    @Before
    public void setUp() throws Exception {
        properties = new GrayskullProperties();
        properties.setHost("https://test.grayskull.com");
        properties.setConnectionTimeout(5000);
        properties.setReadTimeout(10000);
        
        client = new GrayskullClientImpl(mockAuthProvider, properties);
        
        // Inject mock HTTP client using reflection
        Field httpClientField = GrayskullClientImpl.class.getDeclaredField("httpClient");
        httpClientField.setAccessible(true);
        httpClientField.set(client, mockHttpClient);
    }

    @After
    public void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    public void testGetSecret_success() {
        // Given
        String secretRef = "my-project:database-password";
        SecretValue expectedSecret = SecretValue.builder()
                .dataVersion(1)
                .publicPart("username")
                .privatePart("password123")
                .build();

        when(mockHttpClient.doGet(anyString(), any(TypeReference.class), anyString(), anyString()))
                .thenReturn(expectedSecret);

        // When
        SecretValue result = client.getSecret(secretRef);

        // Then
        assertNotNull(result);
        assertEquals(expectedSecret.getDataVersion(), result.getDataVersion());
        assertEquals(expectedSecret.getPublicPart(), result.getPublicPart());
        assertEquals(expectedSecret.getPrivatePart(), result.getPrivatePart());
        
        // Verify correct URL was called
        verify(mockHttpClient).doGet(
                eq("https://test.grayskull.com/v1/projects/my-project/secrets/database-password/data"),
                any(TypeReference.class),
                eq(secretRef),
                eq("getSecret")
        );
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetSecret_nullSecretRef() {
        // When/Then
        client.getSecret(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetSecret_emptySecretRef() {
        // When/Then
        client.getSecret("");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetSecret_invalidFormat_noColon() {
        // When/Then
        client.getSecret("invalid-format");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetSecret_invalidFormat_emptyProjectId() {
        // When/Then
        client.getSecret(":secret-name");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetSecret_invalidFormat_emptySecretName() {
        // When/Then
        client.getSecret("project-id:");
    }

    @Test
    public void testGetSecret_validFormat_withMultipleColons() {
        // Given
        String secretRef = "my-project:secret:with:colons";
        SecretValue expectedSecret = SecretValue.builder()
                .dataVersion(1)
                .publicPart("pub")
                .privatePart("priv")
                .build();

        when(mockHttpClient.doGet(anyString(), any(TypeReference.class), anyString(), anyString()))
                .thenReturn(expectedSecret);

        // When
        SecretValue result = client.getSecret(secretRef);

        // Then
        assertNotNull(result);
        
        // Verify URL is properly encoded - colons after the first one are encoded as %3A
        verify(mockHttpClient).doGet(
                eq("https://test.grayskull.com/v1/projects/my-project/secrets/secret%3Awith%3Acolons/data"),
                any(TypeReference.class),
                eq(secretRef),
                eq("getSecret")
        );
    }

    @Test
    public void testGetSecret_withSpecialCharacters() {
        // Given - secret name contains @ and # characters
        String secretRef = "project:secret@domain#tag";
        SecretValue expectedSecret = SecretValue.builder()
                .dataVersion(1)
                .publicPart("username")
                .privatePart("password")
                .build();

        when(mockHttpClient.doGet(anyString(), any(TypeReference.class), anyString(), anyString()))
                .thenReturn(expectedSecret);

        // When
        SecretValue result = client.getSecret(secretRef);

        // Then
        assertNotNull(result);
        assertEquals(expectedSecret.getDataVersion(), result.getDataVersion());
        assertEquals(expectedSecret.getPublicPart(), result.getPublicPart());
        assertEquals(expectedSecret.getPrivatePart(), result.getPrivatePart());
        
        // Verify URL is properly encoded with special characters
        // @ should be encoded as %40, # should be encoded as %23
        verify(mockHttpClient).doGet(
                eq("https://test.grayskull.com/v1/projects/project/secrets/secret%40domain%23tag/data"),
                any(TypeReference.class),
                eq(secretRef),
                eq("getSecret")
        );
    }

    @Test(expected = GrayskullException.class)
    public void testGetSecret_nullResponse() {
        // Given
        String secretRef = "project:secret";
        when(mockHttpClient.doGet(anyString(), any(TypeReference.class), anyString(), anyString()))
                .thenReturn(null);

        // When/Then
        client.getSecret(secretRef);
    }

    @Test(expected = GrayskullException.class)
    public void testGetSecret_httpClientThrowsException() {
        // Given
        String secretRef = "project:secret";
        when(mockHttpClient.doGet(anyString(), any(TypeReference.class), anyString(), anyString()))
                .thenThrow(new GrayskullException(500, "Network error"));

        // When/Then
        client.getSecret(secretRef);
    }

    @Test
    public void testRegisterRefreshHook_success() {
        // Given
        String secretRef = "secengg-stage:secret-1";
        AtomicInteger callCount = new AtomicInteger(0);
        
        SecretRefreshHook hook = secretVal -> {
            callCount.incrementAndGet();
            System.out.println("Secret refreshed: " + secretVal);
        };

        // When
        RefreshHookHandle handle = client.registerRefreshHook(secretRef, hook);

        // Then
        assertNotNull(handle);
        assertEquals(secretRef, handle.getSecretRef());
        assertTrue(handle.isActive());
        
        // Verify hook was never called (placeholder implementation)
        assertEquals(0, callCount.get());
    }

    @Test
    public void testRegisterRefreshHook_canUnregister() {
        // Given
        String secretRef = "secengg-stage:secret-1";
        SecretRefreshHook hook = secretVal -> System.out.println("woooo");

        // When
        RefreshHookHandle handle = client.registerRefreshHook(secretRef, hook);
        assertTrue(handle.isActive());
        
        handle.unRegister();

        // Then
        assertFalse(handle.isActive());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRegisterRefreshHook_nullSecretRef() {
        // When/Then
        client.registerRefreshHook(null, secretVal -> {});
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRegisterRefreshHook_emptySecretRef() {
        // When/Then
        client.registerRefreshHook("", secretVal -> {});
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRegisterRefreshHook_nullHook() {
        // When/Then
        client.registerRefreshHook("project:secret", null);
    }

    @Test
    public void testClose_cleansUpResources() {
        // When
        client.close();

        // Then
        verify(mockHttpClient).close();
    }

    @Test
    public void testClose_handlesNullHttpClient() throws Exception {
        // Given - create client with null http client
        GrayskullClientImpl clientWithNullHttp = new GrayskullClientImpl(mockAuthProvider, properties);
        Field httpClientField = GrayskullClientImpl.class.getDeclaredField("httpClient");
        httpClientField.setAccessible(true);
        httpClientField.set(clientWithNullHttp, null);

        // When/Then - should not throw exception
        clientWithNullHttp.close();
    }
}

