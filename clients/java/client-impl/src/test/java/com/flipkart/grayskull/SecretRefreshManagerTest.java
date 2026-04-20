package com.flipkart.grayskull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.flipkart.grayskull.hooks.SecretRefreshHook;
import com.flipkart.grayskull.models.SecretValue;
import com.flipkart.grayskull.models.response.BatchGetSecretsResponse;
import com.flipkart.grayskull.models.response.BatchSecretItem;
import com.flipkart.grayskull.models.response.HttpResponse;
import com.flipkart.grayskull.models.response.Response;

@ExtendWith(MockitoExtension.class)
class SecretRefreshManagerTest {

    @Mock
    private GrayskullHttpClient mockHttpClient;

    private ObjectMapper objectMapper;
    private SecretRefreshManager manager;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new ParameterNamesModule());
        manager = new SecretRefreshManager(mockHttpClient, "http://localhost/v1/secrets/batch",
                objectMapper, 30);
    }

    @Test
    void register_addsHookAndReturnsUniqueIds() {
        long id1 = manager.register("proj:s1", (s) -> {}, 1);
        long id2 = manager.register("proj:s2", (s) -> {}, 1);
        long id3 = manager.register("proj:s1", (s) -> {}, 1);

        assertEquals(3, manager.hookCount());
        assertEquals(2, manager.secretCount());
        assertTrue(id1 > 0);
        assertTrue(id2 != id1);
        assertTrue(id3 != id2 && id3 != id1);
    }

    @Test
    void poll_withNoHooks_doesNothing() {
        manager.poll();
        verifyNoInteractions(mockHttpClient);
    }

    @Test
    void poll_callsServerAndInvokesHook_whenSecretUpdated() throws Exception {
        AtomicReference<SecretValue> received = new AtomicReference<>();
        SecretRefreshHook hook = received::set;
        manager.register("proj-a:db-pass", hook, 1);

        BatchSecretItem updated = new BatchSecretItem("proj-a", "db-pass", 2, "user", "newpass");
        BatchGetSecretsResponse batchResponse = new BatchGetSecretsResponse(1, Collections.singletonList(updated));
        Response<BatchGetSecretsResponse> response = new Response<>(batchResponse, "Success");
        String json = objectMapper.writeValueAsString(response);

        when(mockHttpClient.doPostWithRetry(anyString(), anyString()))
                .thenReturn(new HttpResponse(200, json, "application/json", "http/1.1"));

        manager.poll();

        assertNotNull(received.get());
        assertEquals(2, received.get().getDataVersion());
        assertEquals("newpass", received.get().getPrivatePart());
        verify(mockHttpClient).doPostWithRetry(eq("http://localhost/v1/secrets/batch"), anyString());
    }

    @Test
    void poll_doesNotInvokeHook_whenNoUpdates() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        manager.register("proj:secret", (s) -> callCount.incrementAndGet(), 5);

        BatchGetSecretsResponse batchResponse = new BatchGetSecretsResponse(0, Collections.emptyList());
        Response<BatchGetSecretsResponse> response = new Response<>(batchResponse, "Success");
        String json = objectMapper.writeValueAsString(response);

        when(mockHttpClient.doPostWithRetry(anyString(), anyString()))
                .thenReturn(new HttpResponse(200, json, "application/json", "http/1.1"));

        manager.poll();

        assertEquals(0, callCount.get());
    }

    @Test
    void poll_handlesServerErrorGracefully() {
        manager.register("proj:secret", (s) -> {}, 1);

        when(mockHttpClient.doPostWithRetry(anyString(), anyString()))
                .thenThrow(new RuntimeException("Server down"));

        manager.poll();
    }

    @Test
    void poll_handlesHookExceptionGracefully_forSeparateSecrets() throws Exception {
        AtomicInteger secondHookCalls = new AtomicInteger(0);
        manager.register("proj:secret-1", (s) -> { throw new RuntimeException("hook error"); }, 1);
        manager.register("proj:secret-2", (s) -> secondHookCalls.incrementAndGet(), 1);

        BatchSecretItem u1 = new BatchSecretItem("proj", "secret-1", 2, "pub1", "priv1");
        BatchSecretItem u2 = new BatchSecretItem("proj", "secret-2", 3, "pub2", "priv2");
        BatchGetSecretsResponse batchResponse = new BatchGetSecretsResponse(2, Arrays.asList(u1, u2));
        Response<BatchGetSecretsResponse> response = new Response<>(batchResponse, "Success");
        String json = objectMapper.writeValueAsString(response);

        when(mockHttpClient.doPostWithRetry(anyString(), anyString()))
                .thenReturn(new HttpResponse(200, json, "application/json", "http/1.1"));

        manager.poll();

        assertEquals(1, secondHookCalls.get());
    }

    @Test
    void poll_handlesNullResponseData() throws Exception {
        manager.register("proj:secret", (s) -> fail("should not be called"), 1);

        Response<BatchGetSecretsResponse> response = new Response<>(null, "Success");
        String json = objectMapper.writeValueAsString(response);

        when(mockHttpClient.doPostWithRetry(anyString(), anyString()))
                .thenReturn(new HttpResponse(200, json, "application/json", "http/1.1"));

        manager.poll();
    }

    @Test
    void poll_handlesNullUpdatedSecretsList() throws Exception {
        manager.register("proj:secret", (s) -> fail("should not be called"), 1);

        BatchGetSecretsResponse batchResponse = new BatchGetSecretsResponse(0, null);
        Response<BatchGetSecretsResponse> response = new Response<>(batchResponse, "Success");
        String json = objectMapper.writeValueAsString(response);

        when(mockHttpClient.doPostWithRetry(anyString(), anyString()))
                .thenReturn(new HttpResponse(200, json, "application/json", "http/1.1"));

        manager.poll();
    }

    @Test
    void shutdown_doesNotThrowWhenSchedulerNotStarted() {
        manager.shutdown();
    }

    @Test
    void shutdown_stopsRunningScheduler() {
        // Starts the scheduler as a side effect.
        manager.register("proj:secret", (s) -> {}, 1);
        manager.shutdown();
        // No exception; a second shutdown is also safe.
        manager.shutdown();
    }

    @Test
    void poll_multipleHooksPerSecret_areInvokedInRegistrationOrder_onUpdate() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        manager.register("proj:s", (v) -> order.add("A"), 1);
        manager.register("proj:s", (v) -> order.add("B"), 1);
        manager.register("proj:s", (v) -> order.add("C"), 1);

        BatchSecretItem u = new BatchSecretItem("proj", "s", 2, "pub", "priv");
        BatchGetSecretsResponse batch = new BatchGetSecretsResponse(1, Collections.singletonList(u));
        String json = objectMapper.writeValueAsString(new Response<>(batch, "Success"));
        when(mockHttpClient.doPostWithRetry(anyString(), anyString()))
                .thenReturn(new HttpResponse(200, json, "application/json", "http/1.1"));

        manager.poll();

        assertEquals(Arrays.asList("A", "B", "C"), order);
    }

    @Test
    void poll_firstHookThrowing_stillInvokesSubsequentHooksForSameSecret() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        manager.register("proj:s", (v) -> { order.add("A-threw"); throw new RuntimeException("boom"); }, 1);
        manager.register("proj:s", (v) -> order.add("B"), 1);

        BatchSecretItem u = new BatchSecretItem("proj", "s", 2, "pub", "priv");
        String json = objectMapper.writeValueAsString(
                new Response<>(new BatchGetSecretsResponse(1, Collections.singletonList(u)), "Success"));
        when(mockHttpClient.doPostWithRetry(anyString(), anyString()))
                .thenReturn(new HttpResponse(200, json, "application/json", "http/1.1"));

        manager.poll();

        assertEquals(Arrays.asList("A-threw", "B"), order);
    }

    @Test
    void poll_lateRegisteredHookAtCurrentVersion_isNotReinvoked() throws Exception {
        // Hook A registered at v1; hook B registered later at v2. Server says
        // current version is 2 → only A should be invoked; B is already caught up.
        AtomicInteger aCalls = new AtomicInteger(0);
        AtomicInteger bCalls = new AtomicInteger(0);
        manager.register("proj:s", (v) -> aCalls.incrementAndGet(), 1);
        manager.register("proj:s", (v) -> bCalls.incrementAndGet(), 2);

        BatchSecretItem u = new BatchSecretItem("proj", "s", 2, "pub", "priv");
        String json = objectMapper.writeValueAsString(
                new Response<>(new BatchGetSecretsResponse(1, Collections.singletonList(u)), "Success"));
        when(mockHttpClient.doPostWithRetry(anyString(), anyString()))
                .thenReturn(new HttpResponse(200, json, "application/json", "http/1.1"));

        manager.poll();

        assertEquals(1, aCalls.get());
        assertEquals(0, bCalls.get());
    }

    @Test
    void poll_hookThrowing_doesNotAdvanceLastKnownVersion_andIsRetriedNextPoll() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        manager.register("proj:s", (v) -> {
            attempts.incrementAndGet();
            throw new RuntimeException("flaky");
        }, 1);

        BatchSecretItem u = new BatchSecretItem("proj", "s", 2, "pub", "priv");
        String json = objectMapper.writeValueAsString(
                new Response<>(new BatchGetSecretsResponse(1, Collections.singletonList(u)), "Success"));
        when(mockHttpClient.doPostWithRetry(anyString(), anyString()))
                .thenReturn(new HttpResponse(200, json, "application/json", "http/1.1"));

        manager.poll();
        manager.poll();

        // Hook retried both times because its lastKnownVersion did not advance.
        assertEquals(2, attempts.get());
    }

    @Test
    void poll_minLastKnownVersionIsSentToServer_whenHooksAtDifferentVersions() throws Exception {
        manager.register("proj:s", (v) -> {}, 5);
        manager.register("proj:s", (v) -> {}, 2);

        when(mockHttpClient.doPostWithRetry(anyString(), anyString()))
                .thenReturn(new HttpResponse(200,
                        objectMapper.writeValueAsString(
                                new Response<>(new BatchGetSecretsResponse(0, Collections.emptyList()), "Success")),
                        "application/json", "http/1.1"));

        manager.poll();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockHttpClient).doPostWithRetry(anyString(), bodyCaptor.capture());
        // Minimum across hooks for this secret is 2.
        assertTrue(bodyCaptor.getValue().contains("\"lastKnownVersion\":2"),
                "Expected min lastKnownVersion 2 in request, got: " + bodyCaptor.getValue());
    }

    @Test
    void poll_respectsBatchSizeOf50() throws Exception {
        // Register 75 secrets → expect 2 POSTs (50 + 25).
        for (int i = 0; i < 75; i++) {
            manager.register("proj:s" + i, (v) -> {}, 1);
        }
        when(mockHttpClient.doPostWithRetry(anyString(), anyString()))
                .thenReturn(new HttpResponse(200,
                        objectMapper.writeValueAsString(
                                new Response<>(new BatchGetSecretsResponse(0, new ArrayList<>()), "Success")),
                        "application/json", "http/1.1"));

        manager.poll();

        verify(mockHttpClient, times(2)).doPostWithRetry(anyString(), anyString());
    }

    @Test
    void unregister_removesSingleHook_andLeavesSiblingHooksActive() throws Exception {
        AtomicInteger aCalls = new AtomicInteger(0);
        AtomicInteger bCalls = new AtomicInteger(0);
        long idA = manager.register("proj:s", (v) -> aCalls.incrementAndGet(), 1);
        manager.register("proj:s", (v) -> bCalls.incrementAndGet(), 1);

        assertTrue(manager.unregister("proj:s", idA));
        assertEquals(1, manager.hookCount());
        assertEquals(1, manager.secretCount());

        BatchSecretItem u = new BatchSecretItem("proj", "s", 2, "pub", "priv");
        String json = objectMapper.writeValueAsString(
                new Response<>(new BatchGetSecretsResponse(1, Collections.singletonList(u)), "Success"));
        when(mockHttpClient.doPostWithRetry(anyString(), anyString()))
                .thenReturn(new HttpResponse(200, json, "application/json", "http/1.1"));

        manager.poll();

        assertEquals(0, aCalls.get());
        assertEquals(1, bCalls.get());
    }

    @Test
    void unregister_whenLastHookRemoved_prunesSecretAndSkipsServerCall() {
        long id = manager.register("proj:s", (v) -> {}, 1);
        assertTrue(manager.unregister("proj:s", id));
        assertEquals(0, manager.hookCount());
        assertEquals(0, manager.secretCount());

        manager.poll();
        verify(mockHttpClient, never()).doPostWithRetry(anyString(), anyString());
    }

    @Test
    void unregister_unknownSecret_returnsFalse() {
        assertFalse(manager.unregister("unknown:secret", 999L));
    }

    @Test
    void unregister_unknownHookId_returnsFalse() {
        manager.register("proj:s", (v) -> {}, 1);
        assertFalse(manager.unregister("proj:s", 99999L));
        // Existing hook unaffected.
        assertEquals(1, manager.hookCount());
    }

    @Test
    void poll_skipsMalformedSecretRefs_ratherThanFailingBatch() throws Exception {
        // A single-token "badref" should never be accepted by the client layer,
        // but the manager guards against it anyway so one bad entry doesn't
        // poison the rest of the batch.
        manager.register("badref", (v) -> {}, 1);
        manager.register("proj:good", (v) -> {}, 1);

        when(mockHttpClient.doPostWithRetry(anyString(), anyString()))
                .thenReturn(new HttpResponse(200,
                        objectMapper.writeValueAsString(
                                new Response<>(new BatchGetSecretsResponse(0, Collections.emptyList()), "Success")),
                        "application/json", "http/1.1"));

        manager.poll();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockHttpClient).doPostWithRetry(anyString(), bodyCaptor.capture());
        // Good secret is present; malformed one is dropped.
        assertTrue(bodyCaptor.getValue().contains("\"secretName\":\"good\""));
        assertFalse(bodyCaptor.getValue().contains("badref"));
    }

    @Test
    void poll_skipsEntirelyWhenBatchContainsOnlyMalformedRefs() {
        manager.register("badref", (v) -> {}, 1);

        manager.poll();

        // No valid entries → no HTTP call made at all.
        verify(mockHttpClient, never()).doPostWithRetry(anyString(), anyString());
    }

    @Test
    void poll_updatedSecretWithNoRegisteredHooks_isIgnored() throws Exception {
        // Server returns an update for a secret we no longer track (e.g. raced
        // with unregister). Manager must skip cleanly.
        long id = manager.register("proj:s", (v) -> fail("should not be called"), 1);
        manager.unregister("proj:s", id);

        // Re-register a DIFFERENT secret so the poll actually fires.
        AtomicInteger otherCalls = new AtomicInteger(0);
        manager.register("proj:other", (v) -> otherCalls.incrementAndGet(), 1);

        BatchSecretItem stale = new BatchSecretItem("proj", "s", 2, "pub", "priv");
        BatchSecretItem current = new BatchSecretItem("proj", "other", 2, "pub", "priv");
        String json = objectMapper.writeValueAsString(
                new Response<>(new BatchGetSecretsResponse(2, Arrays.asList(stale, current)), "Success"));
        when(mockHttpClient.doPostWithRetry(anyString(), anyString()))
                .thenReturn(new HttpResponse(200, json, "application/json", "http/1.1"));

        manager.poll();
        assertEquals(1, otherCalls.get());
    }

    @Test
    void poll_urlAndPayloadStructure_isCorrect() throws Exception {
        manager.register("proj-x:k1", (v) -> {}, 7);

        when(mockHttpClient.doPostWithRetry(anyString(), anyString()))
                .thenReturn(new HttpResponse(200,
                        objectMapper.writeValueAsString(
                                new Response<>(new BatchGetSecretsResponse(0, Collections.emptyList()), "Success")),
                        "application/json", "http/1.1"));

        manager.poll();
        verify(mockHttpClient).doPostWithRetry(
                eq("http://localhost/v1/secrets/batch"),
                contains("\"projectId\":\"proj-x\""));
    }
}
