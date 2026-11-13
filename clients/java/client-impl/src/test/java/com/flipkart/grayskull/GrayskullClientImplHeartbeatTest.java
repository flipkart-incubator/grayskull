package com.flipkart.grayskull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.constants.GrayskullHeaders;
import com.flipkart.grayskull.models.GrayskullClientConfiguration;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Heartbeat behaviour of {@link GrayskullClientImpl} against a fake server. */
class GrayskullClientImplHeartbeatTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<RecordedRequest> heartbeats = new CopyOnWriteArrayList<>();
    /** When > 0, refresh-hook polls (not heartbeats) report proj:db-password updated to this version. */
    private volatile int pollVersion;
    private MockWebServer server;
    private GrayskullClientImpl client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if ("GET".equals(request.getMethod()) && path.endsWith("/data")) {
                    return json("{\"data\":{\"dataVersion\":7,\"publicPart\":\"pub\",\"privatePart\":\"priv\"},\"message\":\"ok\"}");
                }
                if ("POST".equals(request.getMethod()) && path.equals("/v1/heartbeat")) {
                    heartbeats.add(request);
                    return new MockResponse().setResponseCode(204);
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

    private GrayskullClientConfiguration config(WorkloadIdentityResolver resolver) {
        GrayskullClientConfiguration config = new GrayskullClientConfiguration();
        config.setHost(server.url("/").toString().replaceAll("/$", ""));
        config.setMetricsEnabled(false);
        config.setHeartbeatIntervalSeconds(1);
        config.setWorkloadIdentityResolver(resolver);
        return config;
    }

    private static WorkloadIdentityResolver workloadResolver() {
        return new WorkloadIdentityResolver() {
            @Override
            public String resolve() {
                return "v1::c::ns::ReplicaSetOwned::app";
            }
        };
    }

    private void waitForHeartbeats(int count, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (heartbeats.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void heartbeat_reportsFetchedSecretsWithIdentityHeaders() throws Exception {
        client = new GrayskullClientImpl(() -> "Bearer test-token", config(workloadResolver()));
        client.getSecret("proj:db-password");

        waitForHeartbeats(1, 5_000);
        assertTrue(heartbeats.size() >= 1, "expected a heartbeat");
        RecordedRequest hb = heartbeats.get(0);

        assertEquals("v1::c::ns::ReplicaSetOwned::app", hb.getHeader(GrayskullHeaders.WORKLOAD));
        assertTrue(hb.getHeader("User-Agent").startsWith("grayskull-java/"));
        JsonNode secrets = objectMapper.readTree(hb.getBody().readUtf8()).get("secrets");
        assertEquals(1, secrets.size());
        assertEquals("proj", secrets.get(0).get("projectId").asText());
        assertEquals("db-password", secrets.get(0).get("secretName").asText());
        assertEquals(7, secrets.get(0).get("version").asInt());
        assertEquals(false, secrets.get(0).get("refreshHook").asBoolean());
    }

    @Test
    void heartbeat_nothingSentBeforeFirstFetch() throws Exception {
        client = new GrayskullClientImpl(() -> "Bearer test-token", config(workloadResolver()));

        Thread.sleep(1_500);
        assertEquals(0, heartbeats.size());
    }

    @Test
    void heartbeat_disabledSendsNothing() throws Exception {
        GrayskullClientConfiguration config = config(workloadResolver());
        config.setHeartbeatEnabled(false);
        client = new GrayskullClientImpl(() -> "Bearer test-token", config);
        client.getSecret("proj:db-password");

        Thread.sleep(1_500);
        assertEquals(0, heartbeats.size());
    }

    @Test
    void heartbeat_reportsVersionDeliveredToRefreshHook() throws Exception {
        pollVersion = 8;
        GrayskullClientConfiguration config = config(workloadResolver());
        config.setPollingIntervalSeconds(1);
        config.setHeartbeatIntervalSeconds(2);
        client = new GrayskullClientImpl(() -> "Bearer test-token", config);
        client.getSecret("proj:db-password"); // v7
        client.registerRefreshHook("proj:db-password", secret -> { });

        // The poller delivers v8 to the hook; the heartbeat must then report v8, not the fetched v7.
        long deadline = System.currentTimeMillis() + 8_000;
        int reported = -1;
        boolean hook = false;
        while (System.currentTimeMillis() < deadline && reported != 8) {
            for (RecordedRequest hb : heartbeats) {
                JsonNode secrets = objectMapper.readTree(hb.getBody().clone().readUtf8()).get("secrets");
                reported = secrets.get(0).get("version").asInt();
                hook = secrets.get(0).get("refreshHook").asBoolean();
            }
            Thread.sleep(50);
        }
        assertEquals(8, reported);
        assertTrue(hook, "refreshHook is true once a hook is registered");
    }
}
