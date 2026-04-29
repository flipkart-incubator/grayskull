package com.flipkart.grayskull.models.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Jackson round-trip tests for {@link BatchGetSecretsRequest}. */
class BatchGetSecretsRequestTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new ParameterNamesModule());

    @Test
    void serialize_producesCanonicalShape() throws Exception {
        List<BatchGetSecretsRequest.Entry> entries = Arrays.asList(
                new BatchGetSecretsRequest.Entry("p1", "s1", 0),
                new BatchGetSecretsRequest.Entry("p2", "s2", 7)
        );
        String json = mapper.writeValueAsString(new BatchGetSecretsRequest(entries));

        JsonNode root = mapper.readTree(json);
        assertEquals(2, root.get("secrets").size());
        assertEquals("p1", root.get("secrets").get(0).get("projectId").asText());
        assertEquals("s1", root.get("secrets").get(0).get("secretName").asText());
        assertEquals(0, root.get("secrets").get(0).get("lastKnownVersion").asInt());
        assertEquals(7, root.get("secrets").get(1).get("lastKnownVersion").asInt());
    }

    @Test
    void deserialize_byParameterName_populatesAllFields() throws Exception {
        String json = "{\"secrets\":[{\"projectId\":\"p\",\"secretName\":\"s\",\"lastKnownVersion\":3}]}";
        BatchGetSecretsRequest req = mapper.readValue(json, BatchGetSecretsRequest.class);

        assertEquals(1, req.getSecrets().size());
        BatchGetSecretsRequest.Entry e = req.getSecrets().get(0);
        assertEquals("p", e.getProjectId());
        assertEquals("s", e.getSecretName());
        assertEquals(3, e.getLastKnownVersion());
    }

    @Test
    void deserialize_ignoresUnknownProperties_forwardCompatibility() throws Exception {
        String json = "{\"secrets\":[{\"projectId\":\"p\",\"secretName\":\"s\",\"lastKnownVersion\":1," +
                "\"futureField\":\"ignored\"}], \"futureTopLevel\":42}";
        BatchGetSecretsRequest req = mapper.readValue(json, BatchGetSecretsRequest.class);
        assertEquals(1, req.getSecrets().size());
    }

    @Test
    void deserialize_emptyList_supported() throws Exception {
        BatchGetSecretsRequest req = mapper.readValue("{\"secrets\":[]}", BatchGetSecretsRequest.class);
        assertEquals(0, req.getSecrets().size());
    }

    @Test
    void roundTrip_preservesAllFields() throws Exception {
        BatchGetSecretsRequest original = new BatchGetSecretsRequest(
                Collections.singletonList(new BatchGetSecretsRequest.Entry("proj", "name", 42)));
        BatchGetSecretsRequest restored =
                mapper.readValue(mapper.writeValueAsString(original), BatchGetSecretsRequest.class);
        BatchGetSecretsRequest.Entry e = restored.getSecrets().get(0);
        assertEquals("proj", e.getProjectId());
        assertEquals("name", e.getSecretName());
        assertEquals(42, e.getLastKnownVersion());
    }
}
