package com.flipkart.grayskull.models.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The heartbeat body must match the shared fixture in {@code clients/testdata/heartbeat},
 * which the Go SDK validates too, so both SDKs send an identical payload.
 */
class HeartbeatRequestTest {

    /** Maven runs tests from the module directory ({@code clients/java/client-impl}). */
    private static final Path FIXTURE = Paths.get("..", "..", "testdata", "heartbeat", "client_heartbeat_v1.json");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesToSharedFixture() throws Exception {
        assertTrue(Files.exists(FIXTURE), "shared fixture missing: " + FIXTURE.toAbsolutePath());

        HeartbeatRequest request = new HeartbeatRequest(Arrays.asList(
                new HeartbeatRequest.HeldSecret("p", "s", 5, 5),
                new HeartbeatRequest.HeldSecret("p", "hook-pending", 3, null),
                new HeartbeatRequest.HeldSecret("p", "no-hook", 2, null)));

        JsonNode expected = objectMapper.readTree(FIXTURE.toFile());
        JsonNode actual = objectMapper.readTree(objectMapper.writeValueAsString(request));
        assertEquals(expected, actual);
    }

    @Test
    void noHook_omitsRefreshHookAppliedVersion() throws Exception {
        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(
                new HeartbeatRequest.HeldSecret("p", "s", 1, null)));
        assertFalse(node.has("refreshHookAppliedVersion"));
        assertEquals(3, node.size());
    }
}
