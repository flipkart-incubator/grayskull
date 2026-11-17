package com.flipkart.grayskull.models.dto.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeartbeatRequestValidationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    /** Same shape as the shared SDK fixture clients/testdata/heartbeat/client_heartbeat_v1.json. */
    private static final String BODY = "{\"secrets\":["
            + "{\"projectId\":\"proj\",\"secretName\":\"db-password\",\"version\":5,\"refreshHookAppliedVersion\":5},"
            + "{\"projectId\":\"proj\",\"secretName\":\"hook-pending\",\"version\":3,\"refreshHookAppliedVersion\":0},"
            + "{\"projectId\":\"proj\",\"secretName\":\"no-hook\",\"version\":2}]}";

    @Test
    void fixtureBody_deserializesAndValidates() throws Exception {
        HeartbeatRequest request = objectMapper.readValue(BODY, HeartbeatRequest.class);

        assertTrue(validator.validate(request).isEmpty(), () -> validator.validate(request).toString());
        assertEquals(3, request.getSecrets().size());
        assertEquals(5, request.getSecrets().get(0).getRefreshHookAppliedVersion());
        assertEquals(0, request.getSecrets().get(1).getRefreshHookAppliedVersion());
        assertNull(request.getSecrets().get(2).getRefreshHookAppliedVersion());
    }

    @Test
    void hookOnlySecret_withVersionZero_isValid() {
        HeartbeatRequest request = new HeartbeatRequest(List.of(new HeldSecret("p", "s", 0, 0)));
        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void negativeVersion_isRejected() {
        HeartbeatRequest request = new HeartbeatRequest(List.of(new HeldSecret("p", "s", -1, null)));
        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void negativeRefreshHookAppliedVersion_isRejected() {
        HeartbeatRequest request = new HeartbeatRequest(List.of(new HeldSecret("p", "s", 1, -1)));
        assertFalse(validator.validate(request).isEmpty());
    }
}
