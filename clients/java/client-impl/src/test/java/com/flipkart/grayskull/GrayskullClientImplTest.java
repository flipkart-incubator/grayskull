package com.flipkart.grayskull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.auth.GrayskullAuthHeaderProvider;
import com.flipkart.grayskull.constants.GrayskullHeaders;
import com.flipkart.grayskull.workload.WorkloadIdentityResolver;
import com.flipkart.grayskull.models.GrayskullClientConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.flipkart.grayskull.models.response.BatchGetSecretsResponse;
import com.flipkart.grayskull.models.response.BatchGetSecretsResponse.UpdatedSecret;
import com.flipkart.grayskull.models.response.HttpResponse;
import com.flipkart.grayskull.models.SecretValue;
import com.flipkart.grayskull.models.exceptions.GrayskullException;
import com.flipkart.grayskull.hooks.RefreshHandlerRef;
import com.flipkart.grayskull.hooks.SecretRefreshHook;
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GrayskullClientImpl.
 */
@ExtendWith(MockitoExtension.class)
class GrayskullClientImplTest {

    private static final String BATCH_URL = "https://test.grayskull.com/v1/secrets/batch";

    @Mock
    private GrayskullAuthHeaderProvider mockAuthProvider;

    @Mock
    private GrayskullHttpClient mockHttpClient;

    private GrayskullClientConfiguration grayskullClientConfiguration;
    private GrayskullClientImpl client;
    private HookRefreshPoller refreshPoller;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        grayskullClientConfiguration = new GrayskullClientConfiguration();
        grayskullClientConfiguration.setHost("https://test.grayskull.com");
        grayskullClientConfiguration.setConnectionTimeout(5000);
        grayskullClientConfiguration.setReadTimeout(10000);

        client = new GrayskullClientImpl(mockAuthProvider, grayskullClientConfiguration);
        objectMapper = new ObjectMapper();

        // Inject mock HTTP client into the client (used by getSecret) and the poller (used by pollOnce).
        Field httpClientField = GrayskullClientImpl.class.getDeclaredField("httpClient");
        httpClientField.setAccessible(true);
        httpClientField.set(client, mockHttpClient);

        Field refreshPollerField = GrayskullClientImpl.class.getDeclaredField("refreshPoller");
        refreshPollerField.setAccessible(true);
        refreshPoller = (HookRefreshPoller) refreshPollerField.get(client);

        Field pollerHttpClientField = HookRefreshPoller.class.getDeclaredField("httpClient");
        pollerHttpClientField.setAccessible(true);
        pollerHttpClientField.set(refreshPoller, mockHttpClient);
    }

    private void pollOnce() {
        refreshPoller.pollOnce();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        reset(mockHttpClient);
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

        // Then - the handle reflects the registered secret and is live until unregistered.
        assertNotNull(handle);
        assertEquals(secretRef, handle.getSecretRef());
        assertTrue(handle.isActive());

        // Hook is invoked by the background poller; it has not fired in this unit test.
        assertEquals(0, callCount.get());
    }

    @Test
    void testRegisterRefreshHook_canUnregister() {
        // Given
        String secretRef = "secengg-stage:secret-1";
        SecretRefreshHook hook = (secretVal) -> System.out.println("test");

        // When
        RefreshHandlerRef handle = client.registerRefreshHook(secretRef, hook);
        assertTrue(handle.isActive());

        handle.unRegister();

        // Then - idempotent and reflected in isActive()
        assertFalse(handle.isActive());
        handle.unRegister(); // second call is a no-op
        assertFalse(handle.isActive());
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
    void testPollOnce_noRegisteredSecrets_doesNotPost() {
        pollOnce();

        verify(mockHttpClient, never()).doPostWithRetry(anyString(), anyString());
    }

    @Test
    void testPollOnce_singleChunk_emptyUpdatedSecrets_onePost() throws Exception {
        client.registerRefreshHook("acme:db-pass", v -> {});
        client.registerRefreshHook("acme:api-key", v -> {});

        HttpResponse emptyBatch = wrapBatch(new BatchGetSecretsResponse(0, Collections.emptyList()));
        when(mockHttpClient.doPostWithRetry(eq(BATCH_URL), anyString())).thenReturn(emptyBatch);

        pollOnce();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockHttpClient, times(1)).doPostWithRetry(eq(BATCH_URL), bodyCaptor.capture());
        JsonNode root = objectMapper.readTree(bodyCaptor.getValue());
        assertEquals(2, root.get("secrets").size());
    }

    @Test
    void testPollOnce_singleChunk_nullData_continuesWithoutUpdates() throws Exception {
        client.registerRefreshHook("acme:one", v -> {});

        Response<BatchGetSecretsResponse> wrapper = new Response<>(null, "ok");
        String json = objectMapper.writeValueAsString(wrapper);
        when(mockHttpClient.doPostWithRetry(eq(BATCH_URL), anyString()))
                .thenReturn(new HttpResponse(200, json, "application/json", "http/1.1"));

        pollOnce();

        verify(mockHttpClient, times(1)).doPostWithRetry(eq(BATCH_URL), anyString());
    }

    @Test
    void testPollOnce_singleChunk_missingUpdatedSecretsKey_continues() throws Exception {
        client.registerRefreshHook("acme:one", v -> {});

        String json = "{\"data\":{\"updatedCount\":0},\"message\":\"Success\"}";
        when(mockHttpClient.doPostWithRetry(eq(BATCH_URL), anyString()))
                .thenReturn(new HttpResponse(200, json, "application/json", "http/1.1"));

        pollOnce();

        verify(mockHttpClient, times(1)).doPostWithRetry(eq(BATCH_URL), anyString());
    }

    @Test
    void testPollOnce_fiftyOneSecrets_twoSequentialPosts() throws Exception {
        for (int i = 0; i < 51; i++) {
            client.registerRefreshHook("corp:svc-" + i, v -> {});
        }

        HttpResponse emptyBatch = wrapBatch(new BatchGetSecretsResponse(0, Collections.emptyList()));
        when(mockHttpClient.doPostWithRetry(eq(BATCH_URL), anyString())).thenReturn(emptyBatch);

        pollOnce();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockHttpClient, times(2)).doPostWithRetry(eq(BATCH_URL), bodyCaptor.capture());
        assertEquals(50, objectMapper.readTree(bodyCaptor.getAllValues().get(0)).get("secrets").size());
        assertEquals(1, objectMapper.readTree(bodyCaptor.getAllValues().get(1)).get("secrets").size());
    }

    @Test
    void testPollOnce_firstChunkGrayskullException_secondChunkStillRuns_preservesFailureStatus()
            throws Exception {
        for (int i = 0; i < 51; i++) {
            client.registerRefreshHook("corp:svc-" + i, v -> {});
        }

        HttpResponse emptyBatch = wrapBatch(new BatchGetSecretsResponse(0, Collections.emptyList()));
        when(mockHttpClient.doPostWithRetry(eq(BATCH_URL), anyString()))
                .thenThrow(new GrayskullException(503, "batch unavailable"))
                .thenReturn(emptyBatch);

        pollOnce();

        verify(mockHttpClient, times(2)).doPostWithRetry(eq(BATCH_URL), anyString());
    }

    @Test
    void testPollOnce_chunkThrowsGrayskullException() {
        client.registerRefreshHook("acme:one", v -> {});

        when(mockHttpClient.doPostWithRetry(eq(BATCH_URL), anyString()))
                .thenThrow(new GrayskullException(401, "unauthorized"));

        assertDoesNotThrow(() -> pollOnce());

        verify(mockHttpClient, times(1)).doPostWithRetry(eq(BATCH_URL), anyString());
    }

    @Test
    void testPollOnce_chunkInvalidJson_wrapsAsInnerFailure() {
        client.registerRefreshHook("acme:one", v -> {});

        when(mockHttpClient.doPostWithRetry(eq(BATCH_URL), anyString()))
                .thenReturn(new HttpResponse(200, "{not-json", "application/json", "http/1.1"));

        assertDoesNotThrow(() -> pollOnce());

        verify(mockHttpClient, times(1)).doPostWithRetry(eq(BATCH_URL), anyString());
    }

    @Test
    void testPollOnce_batchReturnsUpdatedSecret_invokesHook() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        SecretRefreshHook hook = secretVal -> {
            assertEquals(3, secretVal.getDataVersion());
            latch.countDown();
        };
        client.registerRefreshHook("acme:db", hook);

        UpdatedSecret item = new UpdatedSecret("acme", "db", 3, "pub", "priv");
        HttpResponse batch = wrapBatch(new BatchGetSecretsResponse(1, Collections.singletonList(item)));
        when(mockHttpClient.doPostWithRetry(eq(BATCH_URL), anyString())).thenReturn(batch);

        pollOnce();

        assertTrue(latch.await(5, TimeUnit.SECONDS), "hook should run on dispatcher thread");
    }

    @Test
    void testPollOnce_withoutGetSecret_sendsLastKnownVersionZero() throws Exception {
        client.registerRefreshHook("acme:no-get-secret", v -> {});

        HttpResponse emptyBatch = wrapBatch(new BatchGetSecretsResponse(0, Collections.emptyList()));
        when(mockHttpClient.doPostWithRetry(eq(BATCH_URL), anyString())).thenReturn(emptyBatch);

        pollOnce();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockHttpClient, times(1)).doPostWithRetry(eq(BATCH_URL), bodyCaptor.capture());
        JsonNode entry = objectMapper.readTree(bodyCaptor.getValue()).get("secrets").get(0);
        assertEquals("acme", entry.get("projectId").asText());
        assertEquals("no-get-secret", entry.get("secretName").asText());
        assertEquals(0, entry.get("lastKnownVersion").asInt());
    }

    @Test
    void testPollOnce_multipleHooksForSameSecret_runInRegistrationOrder() throws Exception {
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(2);
        client.registerRefreshHook("acme:ordered", v -> {
            order.add(1);
            latch.countDown();
        });
        client.registerRefreshHook("acme:ordered", v -> {
            order.add(2);
            latch.countDown();
        });

        UpdatedSecret item = new UpdatedSecret("acme", "ordered", 9, "a", "b");
        HttpResponse batch = wrapBatch(new BatchGetSecretsResponse(1, Collections.singletonList(item)));
        when(mockHttpClient.doPostWithRetry(eq(BATCH_URL), anyString())).thenReturn(batch);

        pollOnce();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(Arrays.asList(1, 2), order);
    }

    @Test
    void testPollOnce_hookThrows_swallowsAndContinues() throws Exception {
        // Given - first hook throws, second hook must still run.
        CountDownLatch latch = new CountDownLatch(1);
        client.registerRefreshHook("acme:throws", v -> {
            throw new RuntimeException("consumer bug");
        });
        client.registerRefreshHook("acme:throws", v -> latch.countDown());

        UpdatedSecret item = new UpdatedSecret("acme", "throws", 7, "p", "q");
        HttpResponse batch = wrapBatch(new BatchGetSecretsResponse(1, Collections.singletonList(item)));
        when(mockHttpClient.doPostWithRetry(eq(BATCH_URL), anyString())).thenReturn(batch);

        // When
        pollOnce();

        // Then - the well-behaved hook still fires, proving the poller is fault-isolated.
        assertTrue(latch.await(5, TimeUnit.SECONDS), "second hook should run after first throws");
    }

    @Test
    void testRegisterRefreshHook_twoHooksSameSecret_unregisterOne_otherSurvives() throws Exception {
        // Given - covers the "remove hook but keep state" branch in HookRefreshPoller.unregister.
        CountDownLatch latch = new CountDownLatch(1);
        RefreshHandlerRef h1 = client.registerRefreshHook("acme:two", v -> {});
        RefreshHandlerRef h2 = client.registerRefreshHook("acme:two", v -> latch.countDown());

        // When - unregister only the first hook; the other must still fire on the next poll.
        h1.unRegister();

        UpdatedSecret item = new UpdatedSecret("acme", "two", 4, "p", "q");
        HttpResponse batch = wrapBatch(new BatchGetSecretsResponse(1, Collections.singletonList(item)));
        when(mockHttpClient.doPostWithRetry(eq(BATCH_URL), anyString())).thenReturn(batch);
        pollOnce();

        // Then
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertFalse(h1.isActive());
        assertTrue(h2.isActive());
    }

    @Test
    void testRegisterRefreshHook_invalidFormat_throwsBeforeRegistering() {
        assertThrows(IllegalArgumentException.class,
                () -> client.registerRefreshHook("no-colon", v -> {}));
    }

    @Test
    void testClose_cleansUpResources() {
        // When
        client.close();

        // Then
        verify(mockHttpClient).close();
    }

    @Test
    void testClose_calledTwice_isIdempotent() {
        // First close performs the cleanup; second close must short-circuit
        // via the closed.compareAndSet(false, true) guard and not re-invoke
        // the underlying httpClient.close().
        client.close();
        client.close();

        verify(mockHttpClient, times(1)).close();
    }

    @Test
    void testGetSecret_baseUrlMutatedToInvalid_buildUrlThrows() throws Exception {
        // Given - construct with a valid baseUrl (poller URL is built eagerly),
        // then mutate the baseUrl field to an unparseable value so that
        // HttpUrl.parse() returns null inside buildUrl() during getSecret().
        // This exercises the `throw new IllegalStateException("Invalid baseUrl: ...")`
        // guard in GrayskullClientImpl#buildUrl that the constructor-level
        // failure path cannot reach.
        Field baseUrlField = GrayskullClientImpl.class.getDeclaredField("baseUrl");
        baseUrlField.setAccessible(true);
        baseUrlField.set(client, "::not a url::");

        // When/Then
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.getSecret("project:secret"));
        assertTrue(ex.getMessage().startsWith("Invalid baseUrl:"),
                "exception message must reference the invalid baseUrl: " + ex.getMessage());
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
    
    @Test
    void testConstructor_populatesWorkloadHeader_fromDefaultResolver() {
        // Given - a fresh config uses DefaultWorkloadIdentityResolver (hostname)
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        config.setHost("https://test.grayskull.com");

        // When
        GrayskullClientImpl c = new GrayskullClientImpl(mockAuthProvider, config);

        // Then - the header is present and non-empty
        String injected = config.getDefaultHeaders().get(GrayskullHeaders.WORKLOAD);
        assertNotNull(injected);
        assertFalse(injected.isEmpty());

        c.close();
    }

    @Test
    void testConstructor_populatesWorkloadHeader_fromCustomResolver() {
        // Given - an override resolver is honoured end-to-end
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        config.setHost("https://test.grayskull.com");
        config.setWorkloadIdentityResolver((WorkloadIdentityResolver) () -> "custom-workload-id");

        // When
        GrayskullClientImpl c = new GrayskullClientImpl(mockAuthProvider, config);

        // Then
        assertEquals("custom-workload-id", config.getDefaultHeaders().get(GrayskullHeaders.WORKLOAD));

        c.close();
    }

    @Test
    void testConstructor_resolvesIdentityExactlyOnce() {
        // Given - guard against the resolve() call being moved to the request path
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        config.setHost("https://test.grayskull.com");
        AtomicInteger resolveCount = new AtomicInteger(0);
        config.setWorkloadIdentityResolver(() -> {
            resolveCount.incrementAndGet();
            return "once";
        });

        // When
        GrayskullClientImpl c = new GrayskullClientImpl(mockAuthProvider, config);

        // Then - exactly one resolve() call regardless of subsequent usage
        assertEquals(1, resolveCount.get());
        assertEquals("once", config.getDefaultHeaders().get(GrayskullHeaders.WORKLOAD));

        c.close();
    }

    @Test
    void testConstructor_populatesUserAgent_sdkProductAndVersionOnly() {
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        config.setHost("https://test.grayskull.com");
        config.setWorkloadIdentityResolver(() -> "wl-fixed");

        GrayskullClientImpl c = new GrayskullClientImpl(mockAuthProvider, config);
        String ua = config.getDefaultHeaders().get(GrayskullHeaders.USER_AGENT);

        assertNotNull(ua);
        // UA is strictly grayskull-java/<version>; no workload comment, no spaces.
        assertTrue(ua.matches("^grayskull-java/\\S+$"), ua);
        c.close();
    }

    @Test
    void testConstructor_userAgent_doesNotLeakWorkloadIdentity() {
        // Regression guard: identity must not be embedded in User-Agent. UA is for
        // SDK telemetry only; caller identity belongs solely in Grayskull-Workload.
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        config.setHost("https://test.grayskull.com");
        config.setWorkloadIdentityResolver(() -> "wl-abc");

        GrayskullClientImpl c = new GrayskullClientImpl(mockAuthProvider, config);
        String ua = config.getDefaultHeaders().get(GrayskullHeaders.USER_AGENT);

        assertNotNull(ua);
        assertFalse(ua.contains("wl-abc"), ua);
        assertFalse(ua.contains("("), ua);
        assertFalse(ua.contains(")"), ua);
        c.close();
    }

    @Test
    void testConstructor_userAgent_isStableAcrossIdentityShapes() {
        // Regression guard for identity rotation / shape evolution: even if the
        // resolved identity contains characters that would previously have broken
        // UA parsing (spaces, parens, SPIFFE-style URIs), UA must remain a clean
        // grayskull-java/<version> string.
        String[] exoticIdentities = new String[] {
                "spiffe://flipkart/ns/payments/sa/checkout",
                "pod-with (parens) and spaces",
                "identity\nwith\nnewlines-sanitized-upstream",
                ""
        };
        for (String id : exoticIdentities) {
            GrayskullClientConfiguration config = new GrayskullClientConfiguration();
            config.setHost("https://test.grayskull.com");
            config.setWorkloadIdentityResolver(() -> id);

            GrayskullClientImpl c = new GrayskullClientImpl(mockAuthProvider, config);
            String ua = config.getDefaultHeaders().get(GrayskullHeaders.USER_AGENT);

            assertNotNull(ua, "UA must be present for identity: " + id);
            assertTrue(ua.matches("^grayskull-java/\\S+$"),
                    "UA must be grayskull-java/<version> regardless of identity shape; got: " + ua + " for id: " + id);
            if (!id.isEmpty()) {
                assertFalse(ua.contains(id),
                        "UA must not embed workload identity; got: " + ua + " for id: " + id);
            }
            c.close();
        }
    }

    @Test
    void testConstructor_userAgent_sdkValueWinsOverUserSeededUserAgent() {
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        config.setHost("https://test.grayskull.com");
        config.addDefaultHeader(GrayskullHeaders.USER_AGENT, "curl/1.0");

        GrayskullClientImpl c = new GrayskullClientImpl(mockAuthProvider, config);
        String ua = config.getDefaultHeaders().get(GrayskullHeaders.USER_AGENT);
        assertNotNull(ua);
        assertTrue(ua.startsWith("grayskull-java/"), ua);
        assertFalse(ua.contains("curl"));
        c.close();
    }

    @Test
    void testConstructor_sdkWorkloadWinsOverUserSeededWorkloadHeader() {
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        config.setHost("https://test.grayskull.com");
        config.setWorkloadIdentityResolver(() -> "sdk-workload");
        config.addDefaultHeader(GrayskullHeaders.WORKLOAD, "spoof");

        GrayskullClientImpl c = new GrayskullClientImpl(mockAuthProvider, config);
        assertEquals("sdk-workload", config.getDefaultHeaders().get(GrayskullHeaders.WORKLOAD));
        c.close();
    }

    @Test
    void testGetSecret_emitsSingleUserAgentHeaderOnWire() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            GrayskullClientConfiguration cfg = new GrayskullClientConfiguration();
            cfg.setHost(server.url("/").toString().replaceAll("/$", ""));
            cfg.setConnectionTimeout(5000);
            cfg.setReadTimeout(5000);
            cfg.setMaxRetries(2);
            cfg.setMinRetryDelay(50);
            cfg.setMetricsEnabled(false);

            SecretValue sv = new SecretValue(1, "a", "b");
            Response<SecretValue> resp = new Response<>(sv, "Success");
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setBody(objectMapper.writeValueAsString(resp))
                    .addHeader("Content-Type", "application/json"));

            cfg.setWorkloadIdentityResolver(() -> "wire-test-workload");
            GrayskullClientImpl c = new GrayskullClientImpl(() -> "Bearer tok", cfg);
            c.getSecret("p:s");

            RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(req);

            // User-Agent: one value, SDK shape only (no workload string).
            assertEquals(1, req.getHeaders().values("User-Agent").size());
            String ua = req.getHeader("User-Agent");
            assertNotNull(ua);
            assertTrue(ua.matches("^grayskull-java/\\S+$"), ua);
            assertFalse(ua.contains("wire-test-workload"), ua);

            // Grayskull-Workload: one value from resolver.
            assertEquals(1, req.getHeaders().values(GrayskullHeaders.WORKLOAD).size());
            assertEquals("wire-test-workload", req.getHeader(GrayskullHeaders.WORKLOAD));

            c.close();
        } finally {
            server.shutdown();
        }
    }

    @Test
    void testResolveSdkVersion_returnsUnknownWhenResourceMissing() {
        ClassLoader cl = new ClassLoader(ClassLoader.getSystemClassLoader()) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if ("grayskull-client.properties".equals(name)) {
                    return null;
                }
                return super.getResourceAsStream(name);
            }
        };
        assertEquals("unknown", GrayskullClientImpl.resolveSdkVersion(cl));
    }

    @Test
    void testResolveSdkVersion_returnsUnknownWhenLoadThrowsIOException() {
        ClassLoader cl = new ClassLoader(ClassLoader.getSystemClassLoader()) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if ("grayskull-client.properties".equals(name)) {
                    return new InputStream() {
                        @Override
                        public int read() throws IOException {
                            throw new IOException("fail");
                        }
                    };
                }
                return super.getResourceAsStream(name);
            }
        };
        assertEquals("unknown", GrayskullClientImpl.resolveSdkVersion(cl));
    }

    @Test
    void testResolveSdkVersion_returnsUnknownWhenVersionIsMavenPlaceholder() {
        byte[] props = "version=${project.version}\n".getBytes(StandardCharsets.UTF_8);
        ClassLoader cl = new ClassLoader(ClassLoader.getSystemClassLoader()) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if ("grayskull-client.properties".equals(name)) {
                    return new ByteArrayInputStream(props);
                }
                return super.getResourceAsStream(name);
            }
        };
        assertEquals("unknown", GrayskullClientImpl.resolveSdkVersion(cl));
    }

    @Test
    void testResolveSdkVersion_readsVersionFromClasspathWhenFiltered() {
        String v = GrayskullClientImpl.resolveSdkVersion(GrayskullClientImpl.class.getClassLoader());
        assertNotEquals("unknown", v);
        assertFalse(v.startsWith("${"));
    }

    @Test
    void testGetSecret_malformedJson_throwsGrayskullException() throws Exception {
        HttpResponse httpResponse = new HttpResponse(200, "{not-json", "application/json", "http/1.1");
        when(mockHttpClient.doGetWithRetry(anyString())).thenReturn(httpResponse);

        GrayskullException ex = assertThrows(GrayskullException.class,
                () -> client.getSecret("project:secret"));
        assertTrue(ex.getMessage().contains("Failed to parse response"));
        assertTrue(ex.getCause() instanceof JsonProcessingException);
    }

    @Test
    void testGetSecret_invalidBaseUrl_throwsIllegalStateException() {
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        config.setHost("http://%%:bad");
        // The poller validates baseUrl during construction, so the failure surfaces there.
        assertThrows(IllegalStateException.class,
                () -> new GrayskullClientImpl(mockAuthProvider, config));
    }

    @Test
    void testResolveSdkVersion_unknownWhenVersionKeyMissing() {
        byte[] props = "other=x\n".getBytes(StandardCharsets.UTF_8);
        ClassLoader cl = new ClassLoader(ClassLoader.getSystemClassLoader()) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if ("grayskull-client.properties".equals(name)) {
                    return new ByteArrayInputStream(props);
                }
                return super.getResourceAsStream(name);
            }
        };
        assertEquals("unknown", GrayskullClientImpl.resolveSdkVersion(cl));
    }

    @Test
    void testResolveSdkVersion_unknownWhenVersionWhitespaceOnly() {
        byte[] props = "version=   \n".getBytes(StandardCharsets.UTF_8);
        ClassLoader cl = new ClassLoader(ClassLoader.getSystemClassLoader()) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if ("grayskull-client.properties".equals(name)) {
                    return new ByteArrayInputStream(props);
                }
                return super.getResourceAsStream(name);
            }
        };
        assertEquals("unknown", GrayskullClientImpl.resolveSdkVersion(cl));
    }

    @Test
    void testResolveSdkVersion_unknownWhenStreamCloseFailsAfterSuccessfulLoad() throws Exception {
        byte[] props = "version=9.9.9\n".getBytes(StandardCharsets.UTF_8);
        InputStream in = new ByteArrayInputStream(props) {
            @Override
            public void close() throws IOException {
                super.close();
                throw new IOException("close failed");
            }
        };
        ClassLoader cl = new ClassLoader(ClassLoader.getSystemClassLoader()) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if ("grayskull-client.properties".equals(name)) {
                    return in;
                }
                return super.getResourceAsStream(name);
            }
        };
        assertEquals("unknown", GrayskullClientImpl.resolveSdkVersion(cl));
    }

    private HttpResponse createHttpResponse(SecretValue secretValue) throws Exception {
        Response<SecretValue> response = new Response<>(secretValue, "Success");
        String json = objectMapper.writeValueAsString(response);
        return new HttpResponse(200, json, "application/json", "http/1.1");
    }

    private HttpResponse wrapBatch(BatchGetSecretsResponse data) throws Exception {
        Response<BatchGetSecretsResponse> response = new Response<>(data, "Success");
        String json = objectMapper.writeValueAsString(response);
        return new HttpResponse(200, json, "application/json", "http/1.1");
    }
}
