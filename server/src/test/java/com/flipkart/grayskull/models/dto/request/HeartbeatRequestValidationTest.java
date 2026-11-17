package com.flipkart.grayskull.models.dto.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeartbeatRequestValidationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    /** Same shape as the SDKs' shared fixture clients/testdata/heartbeat/client_heartbeat_v1.json. */
    private static final String V2_BODY = "{"
            + "\"schemaVersion\":1,\"trigger\":\"PERIODIC\","
            + "\"sdk\":{\"language\":\"java\",\"version\":\"0.3.0\"},\"sentAt\":1790000000000,"
            + "\"secrets\":["
            + "{\"projectId\":\"p\",\"secretName\":\"s\",\"version\":5,\"refreshHook\":true,"
            + "\"getCount\":120,\"errorCount\":2,\"firstAccessAt\":1789990000000,\"lastAccessAt\":1789999990000,"
            + "\"hookAppliedVersion\":5,\"hookFailureCount\":0,\"lastHookError\":null},"
            + "{\"projectId\":\"p\",\"secretName\":\"hook-only\",\"version\":0,\"refreshHook\":true,"
            + "\"getCount\":0,\"errorCount\":0,\"firstAccessAt\":0,\"lastAccessAt\":0,"
            + "\"hookAppliedVersion\":0,\"hookFailureCount\":1,"
            + "\"lastHookError\":\"java.lang.IllegalStateException: cannot reload pool\"}]}";

    @Test
    void v2Body_deserializesAndValidates_includingHookOnlyVersionZero() throws Exception {
        HeartbeatRequest request = objectMapper.readValue(V2_BODY, HeartbeatRequest.class);

        assertTrue(validator.validate(request).isEmpty(), () -> validator.validate(request).toString());
        assertEquals(1, request.getSchemaVersion());
        assertEquals("PERIODIC", request.getTrigger());
        assertEquals("java", request.getSdk().getLanguage());
        assertEquals("0.3.0", request.getSdk().getVersion());
        assertEquals(2, request.getSecrets().size());

        HeldSecret fetched = request.getSecrets().get(0);
        assertEquals(120L, fetched.getGetCount());
        assertEquals(5, fetched.getHookAppliedVersion());
        assertNull(fetched.getLastHookError());

        HeldSecret hookOnly = request.getSecrets().get(1);
        assertEquals(0, hookOnly.getVersion());
        assertTrue(hookOnly.isRefreshHook());
        assertEquals(1L, hookOnly.getHookFailureCount());
    }

    @Test
    void v1Body_stillValidates() throws Exception {
        HeartbeatRequest request = objectMapper.readValue(
                "{\"secrets\":[{\"projectId\":\"p\",\"secretName\":\"s\",\"version\":3,\"refreshHook\":false}]}",
                HeartbeatRequest.class);
        assertTrue(validator.validate(request).isEmpty());
        assertNull(request.getSchemaVersion());
        assertNull(request.getSecrets().get(0).getGetCount());
    }

    @Test
    void negativeVersion_isRejected() {
        HeartbeatRequest request = new HeartbeatRequest(
                java.util.List.of(new HeldSecret("p", "s", -1, false)));
        assertFalse(validator.validate(request).isEmpty());
    }
}
