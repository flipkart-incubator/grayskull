package com.flipkart.grayskull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.constants.GrayskullHeaders;
import com.flipkart.grayskull.hooks.RefreshHandlerRef;
import com.flipkart.grayskull.models.GrayskullClientConfiguration;
import com.flipkart.grayskull.models.exceptions.GrayskullException;
import com.flipkart.grayskull.workload.WorkloadIdentityResolver;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end heartbeat behaviour of {@link GrayskullClientImpl} against a mock server. */
class GrayskullClientImplHeartbeatTest {

    private static final String HEARTBEAT_PATH = "/v1/client/heartbeat";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<RecordedRequest> heartbeats = new CopyOnWriteArrayList<>();
    /** When > 0, batch polls return this version. */
    private volatile int pollVersion;
    /** Status the heartbeat endpoint answers with. */
    private volatile int heartbeatStatus = 204;

    private MockWebServer server;
    private GrayskullClientImpl client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if ("GET".equals(request.getMethod()) && path.endsWith("/missing/data")) {
                    return new MockResponse().setResponseCode(404).setBody("not found");
                }
                if ("GET".equals(request.getMethod()) && path.endsWith("/data")) {
                    return json("{\"data\":{\"dataVersion\":7,\"publicPart\":\"pub\",\"privatePart\":\"priv\"},\"message\":\"ok\"}");
                }
                if ("POST".equals(request.getMethod()) && path.equals(HEARTBEAT_PATH)) {
                    heartbeats.add(request);
                    return new MockResponse().setResponseCode(heartbeatStatus);
                }
                if ("POST".equals(request.getMethod()) && path.equals("/v1/secrets/batch")) {
                    if (pollVersion > 0) {
                        return json("{\"data\":{\"updatedCount\":1,\"updatedSecrets\":[{\"projectId\":\"proj\","
                                + "\"secretName\":\"db-password\",\"dataVersion\":" + pollVersion
                                + ",\"publicPart\":\"pub\"}]}}");
                    }
                    return json("{\"data\":{\"updatedCount\":0,\"updatedSecrets\":[]}}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            client.close();
        }
        server.shutdown();
        MDC.clear();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body);
    }

    private GrayskullClientConfiguration config() {
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        config.setHost(server.url("/").toString().replaceAll("/$", ""));
        config.setMetricsEnabled(false);
        config.setHeartbeatIntervalSeconds(1);
        config.setWorkloadIdentityResolver(new WorkloadIdentityResolver() {
            @Override
            public String resolve() {
                return "v1::c::ns::ReplicaSetOwned::app";
            }
        });
        return config;
    }

    private GrayskullClientImpl newClient(GrayskullClientConfiguration config) {
        return new GrayskullClientImpl(() -> "Bearer test-token", config);
    }

    private void waitForHeartbeats(int count, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (heartbeats.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(heartbeats.size() >= count, "expected " + count + " heartbeat(s), got " + heartbeats.size());
    }

    private JsonNode body(RecordedRequest request) throws IOException {
        return objectMapper.readTree(request.getBody().clone().readUtf8());
    }

    /** The entry for {@code secretName} in the latest heartbeat, or null. */
    private JsonNode latestEntry(String secretName) throws IOException {
        JsonNode secrets = body(heartbeats.get(heartbeats.size() - 1)).get("secrets");
        for (JsonNode s : secrets) {
            if (secretName.equals(s.get("secretName").asText())) {
                return s;
            }
        }
        return null;
    }

    private static UsageLedger ledgerOf(GrayskullClientImpl client) throws Exception {
        Field f = GrayskullClientImpl.class.getDeclaredField("ledger");
        f.setAccessible(true);
        return (UsageLedger) f.get(client);
    }

    @Test
    void heartbeat_postsFourFieldPayloadWithIdentityHeaders() throws Exception {
        client = newClient(config());
        client.getSecret("proj:db-password");

        waitForHeartbeats(1, 5_000);
        RecordedRequest hb = heartbeats.get(0);
        assertEquals(HEARTBEAT_PATH, hb.getPath());
        assertEquals("v1::c::ns::ReplicaSetOwned::app", hb.getHeader(GrayskullHeaders.WORKLOAD));
        assertTrue(hb.getHeader("User-Agent").startsWith("grayskull-java/"));

        JsonNode body = body(hb);
        assertEquals(1, body.size(), "body is just {secrets: [...]}");
        JsonNode secret = body.get("secrets").get(0);
        assertEquals(1, body.get("secrets").size());
        assertEquals("proj", secret.get("projectId").asText());
        assertEquals("db-password", secret.get("secretName").asText());
        assertEquals(7, secret.get("version").asInt());
        assertFalse(secret.has("refreshHookAppliedVersion"), "no hook: field is absent");
        assertEquals(3, secret.size(), "secret values must never be sent");
    }

    @Test
    void heartbeat_hookOnlySecretIsReportedOnceThePollerDeliversAVersion() throws Exception {
        pollVersion = 8;
        GrayskullClientConfiguration config = config();
        config.setPollingIntervalSeconds(1);
        config.setHeartbeatIntervalSeconds(2);
        client = newClient(config);
        client.registerRefreshHook("proj:db-password", secret -> { }); // never fetched

        JsonNode entry = awaitEntryAtVersion("db-password", 8);
        assertNotNull(entry, "reported once the poller delivers a version");
        assertEquals(8, entry.get("version").asInt());
        assertEquals(8, entry.path("refreshHookAppliedVersion").asInt(), "the hook succeeded at version 8");
    }

    @Test
    void heartbeat_unregisteredHookIsReportedWithoutHook() throws Exception {
        client = newClient(config());
        client.getSecret("proj:db-password"); // holds v7
        RefreshHandlerRef handle = client.registerRefreshHook("proj:db-password", secret -> { });
        handle.unRegister();
        handle.unRegister(); // idempotent: must not drive the count below zero

        waitForHeartbeats(1, 5_000);
        JsonNode entry = latestEntry("db-password");
        assertNotNull(entry, "ledger entries are never removed");
        assertFalse(entry.has("refreshHookAppliedVersion"));
    }

    @Test
    void getSecret_failureIsRethrownUnchangedAndNotRecorded() throws Exception {
        client = newClient(config());
        GrayskullException thrown = assertThrows(GrayskullException.class, () -> client.getSecret("proj:missing"));
        assertEquals(404, thrown.getStatusCode());
        assertTrue(ledgerOf(client).snapshot().isEmpty(), "only successful fetches are recorded");
    }

    @Test
    void getSecret_malformedRefCreatesNoEntry() throws Exception {
        client = newClient(config());
        assertThrows(IllegalArgumentException.class, () -> client.getSecret("nocolon"));
        assertThrows(IllegalArgumentException.class, () -> client.getSecret("proj:"));
        assertTrue(ledgerOf(client).snapshot().isEmpty());
    }

    @Test
    void heartbeat_nothingSentWhileLedgerEmpty() throws Exception {
        client = newClient(config());
        Thread.sleep(1_500);
        assertEquals(0, heartbeats.size());
    }

    @Test
    void heartbeat_disabledSendsNothing() throws Exception {
        GrayskullClientConfiguration config = config();
        config.setHeartbeatEnabled(false);
        client = newClient(config);
        client.getSecret("proj:db-password");
        Thread.sleep(1_500);
        assertEquals(0, heartbeats.size());
    }

    /** Waits for a heartbeat whose entry for {@code secretName} reports {@code version}. */
    private JsonNode awaitEntryAtVersion(String secretName, int version) throws Exception {
        long deadline = System.currentTimeMillis() + 8_000;
        JsonNode entry = null;
        while (System.currentTimeMillis() < deadline) {
            if (!heartbeats.isEmpty()) {
                entry = latestEntry(secretName);
                if (entry != null && entry.get("version").asInt() == version) {
                    return entry;
                }
            }
            Thread.sleep(50);
        }
        return entry;
    }

    @Test
    void heartbeat_reportsVersionAppliedByRefreshHook() throws Exception {
        pollVersion = 8;
        GrayskullClientConfiguration config = config();
        config.setPollingIntervalSeconds(1);
        config.setHeartbeatIntervalSeconds(2);
        client = newClient(config);
        client.getSecret("proj:db-password"); // v7
        client.registerRefreshHook("proj:db-password", secret -> { });

        // hook gets v8; heartbeat must report version 8 and refreshHookAppliedVersion 8
        JsonNode entry = null;
        long deadline = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < deadline) {
            entry = awaitEntryAtVersion("db-password", 8);
            if (entry != null && entry.path("refreshHookAppliedVersion").asInt() == 8) {
                break;
            }
            Thread.sleep(50);
        }
        assertNotNull(entry);
        assertEquals(8, entry.get("version").asInt());
        assertEquals(8, entry.get("refreshHookAppliedVersion").asInt());
    }

    @Test
    void heartbeat_failedHookDoesNotAdvanceRefreshHookAppliedVersion() throws Exception {
        pollVersion = 8;
        GrayskullClientConfiguration config = config();
        config.setPollingIntervalSeconds(1);
        config.setHeartbeatIntervalSeconds(2);
        client = newClient(config);
        client.registerRefreshHook("proj:db-password", secret -> {
            throw new IllegalStateException("cannot reload pool");
        });

        JsonNode entry = awaitEntryAtVersion("db-password", 8);
        assertNotNull(entry);
        assertEquals(8, entry.get("version").asInt(), "version advances before the hook runs (at-most-once)");
        assertFalse(entry.has("refreshHookAppliedVersion"), "only a successful hook run counts");
    }

    @Test
    void heartbeat_serverErrorsNeverReachTheCaller() throws Exception {
        heartbeatStatus = 503;
        client = newClient(config());
        client.getSecret("proj:db-password");
        waitForHeartbeats(1, 5_000);

        // the client keeps working normally after failed heartbeats
        assertEquals(7, client.getSecret("proj:db-password").getDataVersion());
    }

    @Test
    void heartbeat_unsupportedEndpointNeverReachesTheCaller() throws Exception {
        heartbeatStatus = 404;
        client = newClient(config());
        client.getSecret("proj:db-password");
        waitForHeartbeats(1, 5_000);
        assertEquals(7, client.getSecret("proj:db-password").getDataVersion());
    }

    @Test
    void ledger_isSharedWithPollerAndScheduler() throws Exception {
        client = newClient(config());
        UsageLedger ledger = ledgerOf(client);
        Field pollerField = GrayskullClientImpl.class.getDeclaredField("refreshPoller");
        pollerField.setAccessible(true);
        Field pollerLedger = HookRefreshPoller.class.getDeclaredField("ledger");
        pollerLedger.setAccessible(true);
        assertSame(ledger, pollerLedger.get(pollerField.get(client)));
    }
}
