package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.BaseIntegrationTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the Secret and Admin controllers.
 * These tests cover the full application lifecycle including web layer, service layer,
 * and database interactions using a real, ephemeral MongoDB database via Testcontainers.
 */
class SecretControllerIT extends BaseIntegrationTest {

    /**
     * Tests covering the successful execution of controller endpoints.
     */
    @Nested
    class HappyPathTests {

        @Test
        void shouldCreateAndReadSecret() throws Exception {
            final String projectId = "project-create-read";
            final String secretName = "my-secret";
            final String secretValue = "my-secret-value";

            // Act & Assert: Create the secret
            performCreateSecret(projectId, secretName, secretValue)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value(secretName))
                    .andExpect(jsonPath("$.currentDataVersion").value(1));

            // Act & Assert: Read the secret's value
            performReadSecretValue(projectId, secretName)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.privatePart").value(secretValue));
        }

        @Test
        void shouldUpgradeSecret() throws Exception {
            final String projectId = "project-upgrade";
            final String secretName = "upgradable-secret";
            final String upgradedValue = "upgraded-value";
            performCreateSecret(projectId, secretName, "initial-value");

            // Act & Assert: Upgrade the secret
            performUpgradeSecret(projectId, secretName, upgradedValue)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.dataVersion").value(2));

            // Act & Assert: Verify the new value is active
            performReadSecretValue(projectId, secretName)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.privatePart").value(upgradedValue));
        }

        @Test
        void shouldListSecretsAndReadMetadata() throws Exception {
            final String projectId = "project-list-meta";
            performCreateSecret(projectId, "list-secret-1", "v1");
            performCreateSecret(projectId, "list-secret-2", "v2");

            // Act & Assert: List secrets
            performListSecrets(projectId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.secrets", hasSize(2)));

            // Act & Assert: Read metadata of one secret
            performReadSecretMetadata(projectId, "list-secret-1")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("list-secret-1"))
                    .andExpect(jsonPath("$.metadataVersion").exists());
        }

        @Test
        void shouldDeleteSecret() throws Exception {
            final String projectId = "project-delete";
            final String secretName = "deletable-secret";
            performCreateSecret(projectId, secretName, "some-value");

            // Act & Assert: Delete the secret
            performDeleteSecret(projectId, secretName)
                    .andExpect(status().isNoContent());

            // Act & Assert: Verify it's gone
            performReadSecretMetadata(projectId, secretName)
                    .andExpect(status().isNotFound());
        }

        @Test
        void shouldHandlePaginationCorrectly() throws Exception {
            final String projectId = "project-pagination";
            performCreateSecret(projectId, "secret-1", "v1");
            performCreateSecret(projectId, "secret-2", "v2");
            performCreateSecret(projectId, "secret-3", "v3");

            // Act & Assert: Test limit
            performListSecrets(projectId, "limit=2")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.secrets", hasSize(2)));

            // Act & Assert: Test offset (Spring PageRequest uses page number, not item offset)
            performListSecrets(projectId, "limit=2&offset=1")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.secrets", hasSize(1)))
                    .andExpect(jsonPath("$.secrets[0].name").value("secret-3"));

            // Act & Assert: Test empty project
            performListSecrets("empty-project")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.secrets", hasSize(0)));
        }
    }

    /**
     * Tests covering expected failure scenarios, such as invalid input or resource conflicts.
     */
    @Nested
    class FailurePathTests {

        @Test
        void shouldReturnForbiddenWithoutCredentials() throws Exception {
            mockMvc.perform(get("/v1/project/some-project/secrets"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void shouldReturnNotFoundForNonExistentOperations() throws Exception {
            final String projectId = "project-not-found";
            final String secretName = "non-existent-secret";

            performReadSecretMetadata(projectId, secretName).andExpect(status().isNotFound());
            performReadSecretValue(projectId, secretName).andExpect(status().isNotFound());
            performUpgradeSecret(projectId, secretName, "some-value").andExpect(status().isNotFound());
        }

        @Test
        void shouldReturnConflictWhenCreatingDuplicateSecret() throws Exception {
            final String projectId = "project-conflict";
            final String secretName = "duplicate-secret";
            performCreateSecret(projectId, secretName, "value1");

            // Act & Assert: Try to create it again
            performCreateSecret(projectId, secretName, "value2")
                    .andExpect(status().isConflict());
        }

        @Test
        void shouldReturnBadRequestForInvalidInput() throws Exception {
            // Act & Assert: Test with a blank projectId
            performListSecrets(" ")
                    .andExpect(status().isBadRequest());

            // Act & Assert: Test with invalid limit
            performListSecrets("some-project", "limit=101")
                    .andExpect(status().isBadRequest());

            // Act & Assert: Test create with blank name
            performCreateSecret("some-project", " ", "value")
                    .andExpect(status().isBadRequest());
        }
    }
} 