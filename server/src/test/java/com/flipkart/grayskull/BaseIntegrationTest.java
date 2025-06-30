package com.flipkart.grayskull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.models.dto.request.CreateSecretRequest;
import com.flipkart.grayskull.models.dto.request.UpgradeSecretDataRequest;
import com.flipkart.grayskull.models.enums.SecretProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * An abstract base class for integration tests.
 * <p>
 * This class handles the common setup for all integration tests, including:
 * <ul>
 *   <li>Starting a MongoDB Testcontainer.</li>
 *   <li>Configuring the application context to connect to the test database.</li>
 *   <li>Providing common beans like {@link MockMvc} and {@link ObjectMapper}.</li>
 *   <li>Providing reusable helper methods for common API actions.</li>
 * </ul>
 * </p>
 */
@SpringBootTest(classes = TestGrayskullApplication.class)
@Testcontainers
@AutoConfigureMockMvc
public abstract class BaseIntegrationTest {

    protected static final String USER = "user1";
    protected static final String PASSWORD = "password";

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:latest");

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
        registry.add("spring.data.mongodb.database", () -> "test");
        registry.add("grayskull.crypto.keys.test-key", () -> "VGhpcnR5VHdvQnl0ZUxvbmdDcnlwdG9LZXkwMTIzNDU=");
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    // region Helper Methods

    protected ResultActions performCreateSecret(String projectId, String secretName, String secretValue) throws Exception {
        CreateSecretRequest createRequest = buildCreateSecretRequest(secretName, secretValue);
        return mockMvc.perform(post("/v1/project/{projectId}/secrets", projectId)
                .with(httpBasic(USER, PASSWORD))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)));
    }

    protected ResultActions performReadSecretValue(String projectId, String secretName) throws Exception {
        return mockMvc.perform(get("/v1/project/{projectId}/secrets/{secretName}/data", projectId, secretName)
                .with(httpBasic(USER, PASSWORD)));
    }

    protected ResultActions performReadSecretMetadata(String projectId, String secretName) throws Exception {
        return mockMvc.perform(get("/v1/project/{projectId}/secrets/{secretName}", projectId, secretName)
                .with(httpBasic(USER, PASSWORD)));
    }

    protected ResultActions performListSecrets(String projectId, String... queryParams) throws Exception {
        String url = "/v1/project/{projectId}/secrets";
        if (queryParams.length > 0) {
            url += "?" + String.join("&", queryParams);
        }
        return mockMvc.perform(get(url, projectId)
                .with(httpBasic(USER, PASSWORD)));
    }

    protected ResultActions performUpgradeSecret(String projectId, String secretName, String newSecretValue) throws Exception {
        UpgradeSecretDataRequest upgradeRequest = new UpgradeSecretDataRequest();
        upgradeRequest.setPrivatePart(newSecretValue);
        return mockMvc.perform(post("/v1/project/{projectId}/secrets/{secretName}/data", projectId, secretName)
                .with(httpBasic(USER, PASSWORD))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(upgradeRequest)));
    }

    protected ResultActions performDeleteSecret(String projectId, String secretName) throws Exception {
        return mockMvc.perform(delete("/v1/project/{projectId}/secrets/{secretName}", projectId, secretName)
                .with(httpBasic(USER, PASSWORD)));
    }

    protected ResultActions performGetSecretByVersion(String projectId, String secretName, int version) throws Exception {
        return mockMvc.perform(get("/v1/admin/project/{projectId}/secrets/{secretName}/data/{version}", projectId, secretName, version)
                .with(httpBasic(USER, PASSWORD)));
    }

    private CreateSecretRequest buildCreateSecretRequest(String name, String value) {
        CreateSecretRequest.SecretDataPayload payload = new CreateSecretRequest.SecretDataPayload(null, value);
        CreateSecretRequest createRequest = new CreateSecretRequest();
        createRequest.setName(name);
        createRequest.setProvider(SecretProvider.SELF);
        createRequest.setData(payload);
        return createRequest;
    }

    // endregion
} 