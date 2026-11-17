package com.flipkart.grayskull.models.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The v2 heartbeat body must match the shared fixture in {@code clients/testdata/heartbeat},
 * which the Go SDK validates too, so both SDKs send an identical payload.
 */
class HeartbeatRequestTest {

    /** Maven runs tests from the module directory ({@code clients/java/client-impl}). */
    private static final Path FIXTURE = Paths.get("..", "..", "testdata", "heartbeat", "client_heartbeat_v1.json");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesToSharedFixture() throws Exception {
        assertTrue(Files.exists(FIXTURE), "shared fixture missing: " + FIXTURE.toAbsolutePath());

        HeartbeatRequest request = new HeartbeatRequest(
                HeartbeatRequest.SCHEMA_VERSION,
                HeartbeatRequest.TRIGGER_PERIODIC,
                new HeartbeatRequest.Sdk("java", "0.3.0"),
                1790000000000L,
                Arrays.asList(
                        new HeartbeatRequest.HeldSecret("p", "s", 5, true, 120, 2,
                                1789990000000L, 1789999990000L, 5, 0, null),
                        new HeartbeatRequest.HeldSecret("p", "hook-only", 0, true, 0, 0,
                                0, 0, 0, 1, "java.lang.IllegalStateException: cannot reload pool")));

        JsonNode expected = objectMapper.readTree(FIXTURE.toFile());
        JsonNode actual = objectMapper.readTree(objectMapper.writeValueAsString(request));
        assertEquals(expected, actual);
    }
}
