package com.flipkart.grayskull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.models.dto.request.CreateSecretRequest;
import com.flipkart.grayskull.models.dto.request.SecretDataPayload;
import com.flipkart.grayskull.models.dto.request.UpgradeSecretDataRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.flipkart.grayskull.controllers.GrayskullUserRequestPostProcessor.user;
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
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    // region Helper Methods

    protected ResultActions performCreateSecret(String projectId, String secretName, String secretValue, String username) throws Exception {
        return performCreateSecret(projectId, secretName, "public-part", secretValue, username);
    }

    protected ResultActions performCreateSecret(String projectId, String secretName, String publicPart, String secretValue, String username) throws Exception {
        CreateSecretRequest createRequest = buildCreateSecretRequest(secretName, publicPart, secretValue);
        return mockMvc.perform(post("/v1/projects/{projectId}/secrets", projectId)
                .with(user(username))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)));
    }

    protected ResultActions performReadSecretValue(String projectId, String secretName, String username) throws Exception {
        return mockMvc.perform(get("/v1/projects/{projectId}/secrets/{secretName}/data", projectId, secretName)
                .with(user(username)));
    }

    protected ResultActions performReadSecretMetadata(String projectId, String secretName, String username) throws Exception {
        return mockMvc.perform(get("/v1/projects/{projectId}/secrets/{secretName}", projectId, secretName)
                .with(user(username)));
    }

    protected ResultActions performListSecrets(String projectId, String username, String... queryParams) throws Exception {
        String url = "/v1/projects/{projectId}/secrets";
        if (queryParams.length > 0) {
            url += "?" + String.join("&", queryParams);
        }
        return mockMvc.perform(get(url, projectId)
                .with(user(username)));
    }

    protected ResultActions performUpgradeSecret(String projectId, String secretName, String newSecretValue, String username) throws Exception {
        UpgradeSecretDataRequest upgradeRequest = new UpgradeSecretDataRequest();
        upgradeRequest.setPublicPart("public-part");
        upgradeRequest.setPrivatePart(newSecretValue);
        return mockMvc.perform(post("/v1/projects/{projectId}/secrets/{secretName}/data", projectId, secretName)
                .with(user(username))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(upgradeRequest)));
    }

    protected ResultActions performDeleteSecret(String projectId, String secretName, String username) throws Exception {
        return mockMvc.perform(delete("/v1/projects/{projectId}/secrets/{secretName}", projectId, secretName)
                .with(user(username)));
    }

    private CreateSecretRequest buildCreateSecretRequest(String name, String publicPart, String value) {
        SecretDataPayload payload = new SecretDataPayload(publicPart, value);
        CreateSecretRequest createRequest = new CreateSecretRequest();
        createRequest.setName(name);
        createRequest.setProvider("SELF");
        createRequest.setData(payload);
        return createRequest;
    }

    // endregion
} 