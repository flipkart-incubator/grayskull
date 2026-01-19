package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.BaseIntegrationTest;
import com.flipkart.grayskull.models.dto.request.CreateSecretProviderRequest;
import com.flipkart.grayskull.models.dto.request.SecretProviderRequest;
import com.flipkart.grayskull.spi.models.enums.AuthMechanism;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.MediaType;

import java.util.Map;

import static com.flipkart.grayskull.controllers.GrayskullUserRequestPostProcessor.user;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecretProviderControllerIntegrationTest extends BaseIntegrationTest {

    private static final String USERNAME = "admin";
    private static final String PROVIDERS_URL = "/v1/providers";


    @Test
    @Order(1)
    void listProviders_EmptyDatabase_ReturnsEmptyList() throws Exception {
        mockMvc.perform(get(PROVIDERS_URL).with(user(USERNAME)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @Order(2)
    void listProviders_AfterCreatingMultiple_ReturnsAllProviders() throws Exception {
        // Given - Create multiple providers
        performCreateProvider(buildBasicProviderRequest("provider1"));
        performCreateProvider(buildNoneProviderRequest("provider2"));

        // When & Then
        mockMvc.perform(get(PROVIDERS_URL).with(user(USERNAME)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].name", containsInAnyOrder("provider1", "provider2")));
    }

    @Test
    void listProviders_NonAdminUser_ReturnsForbidden() throws Exception {
        mockMvc.perform(get(PROVIDERS_URL).with(user("test-user")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    void createProvider_ValidBasicAuth_ReturnsCreated() throws Exception {
        // Given
        CreateSecretProviderRequest request = new CreateSecretProviderRequest();
        request.setName("basic-provider");
        request.setAuthMechanism(AuthMechanism.BASIC);
        request.setAuthAttributes(Map.of("username", "admin", "password", "secret"));
        request.setPrincipal("test-principal");

        // When & Then
        mockMvc.perform(post(PROVIDERS_URL)
                .with(user(USERNAME))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.name", is("basic-provider")))
                .andExpect(jsonPath("$.authMechanism", is("BASIC")))
                .andExpect(jsonPath("$.principal", is("test-principal")))
                .andExpect(jsonPath("$.authAttributes.keys()", containsInAnyOrder("username","password", "kmsKeyId", "encrypted")))
                .andExpect(jsonPath("$.authAttributes.password", not(is("secret"))))
                .andExpect(jsonPath("$.creationTime", notNullValue()));
    }

    @Test
    void createProvider_ValidNoneAuth_ReturnsCreated() throws Exception {
        // Given
        CreateSecretProviderRequest request = new CreateSecretProviderRequest();
        request.setName("none-provider");
        request.setAuthMechanism(AuthMechanism.NONE);
        request.setAuthAttributes(Map.of("description", "No authentication required"));
        request.setPrincipal("system");

        // When & Then
        mockMvc.perform(post(PROVIDERS_URL)
                .with(user(USERNAME))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is("none-provider")))
                .andExpect(jsonPath("$.authMechanism", is("NONE")));
    }

    @Test
    void createProvider_DuplicateName_ReturnsConflict() throws Exception {
        // Given - Create first provider
        CreateSecretProviderRequest firstRequest = buildBasicProviderRequest("duplicate-provider");
        performCreateProvider(firstRequest);

        // When - Try to create duplicate
        CreateSecretProviderRequest duplicateRequest = buildBasicProviderRequest("duplicate-provider");

        // Then
        mockMvc.perform(post(PROVIDERS_URL)
                .with(user(USERNAME))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(duplicateRequest)))
                .andExpect(status().isConflict());
    }

    @Test
    void createProvider_InvalidRequest_ReturnsBadRequest() throws Exception {
        // Given - Missing required fields
        CreateSecretProviderRequest request = new CreateSecretProviderRequest();
        request.setName(""); // Invalid empty name

        // When & Then
        mockMvc.perform(post(PROVIDERS_URL)
                .with(user(USERNAME))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createProvider_InvalidAuthAttributes_ReturnsBadRequest() throws Exception {
        // Given
        CreateSecretProviderRequest request = new CreateSecretProviderRequest();
        request.setName("basic-provider");
        request.setAuthMechanism(AuthMechanism.BASIC);
        request.setAuthAttributes(Map.of("username", "admin"));
        request.setPrincipal("test-principal");

        // When & Then
        mockMvc.perform(post(PROVIDERS_URL)
                        .with(user(USERNAME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.violations", hasSize(1)));
    }

    @Test
    void getProvider_ExistingProvider_ReturnsProvider() throws Exception {
        // Given - Create a provider first
        CreateSecretProviderRequest createRequest = buildBasicProviderRequest("existing-provider");
        performCreateProvider(createRequest);

        // When & Then
        mockMvc.perform(get(PROVIDERS_URL + "/existing-provider").with(user(USERNAME)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.name", is("existing-provider")))
                .andExpect(jsonPath("$.authMechanism", is("BASIC")));
    }

    @Test
    void getProvider_NonExistentProvider_ReturnsNotFound() throws Exception {
        mockMvc.perform(get(PROVIDERS_URL + "/non-existent").with(user(USERNAME)))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateProvider_ExistingProvider_ReturnsUpdated() throws Exception {
        // Given - Create a provider first
        CreateSecretProviderRequest createRequest = buildBasicProviderRequest("update-provider");
        performCreateProvider(createRequest);

        // When - Update the provider
        SecretProviderRequest updateRequest = new SecretProviderRequest();
        updateRequest.setAuthMechanism(AuthMechanism.OAUTH2);
        updateRequest.setAuthAttributes(Map.of("audience", "test-audience", "issuerUrl", "https://issuer.com"));
        updateRequest.setPrincipal("updated-principal");

        // Then
        mockMvc.perform(put(PROVIDERS_URL + "/update-provider")
                .with(user(USERNAME))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("update-provider")))
                .andExpect(jsonPath("$.authMechanism", is("OAUTH2")))
                .andExpect(jsonPath("$.principal", is("updated-principal")))
                .andExpect(jsonPath("$.authAttributes.keys()", containsInAnyOrder("audience","issuerUrl")));
    }

    @Test
    void updateProvider_NonExistentProvider_ReturnsNotFound() throws Exception {
        // Given
        SecretProviderRequest updateRequest = new SecretProviderRequest();
        updateRequest.setAuthMechanism(AuthMechanism.BASIC);
        updateRequest.setPrincipal("test-principal");
        updateRequest.setAuthAttributes(Map.of("username", "admin", "password", "secret"));

        // When & Then
        mockMvc.perform(put(PROVIDERS_URL + "/non-existent")
                .with(user(USERNAME))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isNotFound());
    }

    // Helper methods
    private void performCreateProvider(CreateSecretProviderRequest request) throws Exception {
        mockMvc.perform(post(PROVIDERS_URL)
                .with(user(USERNAME))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    private CreateSecretProviderRequest buildBasicProviderRequest(String name) {
        CreateSecretProviderRequest request = new CreateSecretProviderRequest();
        request.setName(name);
        request.setAuthMechanism(AuthMechanism.BASIC);
        request.setAuthAttributes(Map.of("username", "admin", "password", "secret"));
        request.setPrincipal("test-principal");
        return request;
    }

    private CreateSecretProviderRequest buildNoneProviderRequest(String name) {
        CreateSecretProviderRequest request = new CreateSecretProviderRequest();
        request.setName(name);
        request.setAuthMechanism(AuthMechanism.NONE);
        request.setAuthAttributes(Map.of("description", "No auth required"));
        request.setPrincipal("system");
        return request;
    }
}
