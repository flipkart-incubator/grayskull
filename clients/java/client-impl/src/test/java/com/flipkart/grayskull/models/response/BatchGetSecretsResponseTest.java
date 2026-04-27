package com.flipkart.grayskull.models.response;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Jackson round-trip tests for {@link BatchGetSecretsResponse}. */
class BatchGetSecretsResponseTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new ParameterNamesModule());

    @Test
    void deserialize_byParameterName_populatesAllFields() throws Exception {
        String json = "{\"updatedCount\":1,\"updatedSecrets\":[{" +
                "\"projectId\":\"p\",\"secretName\":\"s\",\"dataVersion\":4," +
                "\"publicPart\":\"pub\",\"privatePart\":\"priv\"}]}";

        BatchGetSecretsResponse resp = mapper.readValue(json, BatchGetSecretsResponse.class);

        assertEquals(1, resp.getUpdatedCount());
        assertEquals(1, resp.getUpdatedSecrets().size());
        BatchGetSecretsResponse.UpdatedSecret u = resp.getUpdatedSecrets().get(0);
        assertEquals("p", u.getProjectId());
        assertEquals("s", u.getSecretName());
        assertEquals(4, u.getDataVersion());
        assertEquals("pub", u.getPublicPart());
        assertEquals("priv", u.getPrivatePart());
    }

    @Test
    void deserialize_ignoresUnknownProperties_forwardCompatibility() throws Exception {
        String json = "{\"updatedCount\":0,\"updatedSecrets\":[],\"futureField\":\"ignored\"}";
        BatchGetSecretsResponse resp = mapper.readValue(json, BatchGetSecretsResponse.class);
        assertNotNull(resp.getUpdatedSecrets());
        assertTrue(resp.getUpdatedSecrets().isEmpty());
    }

    @Test
    void roundTrip_preservesAllFields() throws Exception {
        BatchGetSecretsResponse original = new BatchGetSecretsResponse(1, Collections.singletonList(
                new BatchGetSecretsResponse.UpdatedSecret("p", "s", 9, "pub", "priv")));
        BatchGetSecretsResponse restored =
                mapper.readValue(mapper.writeValueAsString(original), BatchGetSecretsResponse.class);

        assertEquals(1, restored.getUpdatedCount());
        BatchGetSecretsResponse.UpdatedSecret u = restored.getUpdatedSecrets().get(0);
        assertEquals("p", u.getProjectId());
        assertEquals("s", u.getSecretName());
        assertEquals(9, u.getDataVersion());
        assertEquals("pub", u.getPublicPart());
        assertEquals("priv", u.getPrivatePart());
    }

    @Test
    void toString_doesNotLeakPrivatePart() {
        // Guard rail: Lombok @Getter does NOT generate toString, so the default
        // Object.toString is used and the private part can never leak via logging
        // accidents. This test will fail if a future contributor adds @ToString
        // without excluding privatePart.
        BatchGetSecretsResponse.UpdatedSecret u =
                new BatchGetSecretsResponse.UpdatedSecret("p", "s", 1, "pub", "TOP_SECRET");
        assertTrue(!u.toString().contains("TOP_SECRET"),
                "privatePart must not appear in toString(); got: " + u);
    }
}
