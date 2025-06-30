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

    private static final String ADMIN_USER = "admin";
    private static final String EDITOR_USER = "editor";
    private static final String VIEWER_USER = "viewer";
    private static final String TEST_PROJECT = "test-project";
    private static final String OTHER_PROJECT = "other-project";

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
            performCreateSecret(projectId, secretName, secretValue, ADMIN_USER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value(secretName))
                    .andExpect(jsonPath("$.currentDataVersion").value(1));

            // Act & Assert: Read the secret's value
            performReadSecretValue(projectId, secretName, ADMIN_USER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.privatePart").value(secretValue));
        }

        @Test
        void shouldUpgradeSecret() throws Exception {
            final String projectId = "project-upgrade";
            final String secretName = "upgradable-secret";
            final String upgradedValue = "upgraded-value";
            performCreateSecret(projectId, secretName, "initial-value", ADMIN_USER);

            // Act & Assert: Upgrade the secret
            performUpgradeSecret(projectId, secretName, upgradedValue, ADMIN_USER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.dataVersion").value(2));

            // Act & Assert: Verify the new value is active
            performReadSecretValue(projectId, secretName, ADMIN_USER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.privatePart").value(upgradedValue));
        }

        @Test
        void shouldListSecretsAndReadMetadata() throws Exception {
            final String projectId = "project-list-meta";
            performCreateSecret(projectId, "list-secret-1", "v1", ADMIN_USER);
            performCreateSecret(projectId, "list-secret-2", "v2", ADMIN_USER);

            // Act & Assert: List secrets
            performListSecrets(projectId, ADMIN_USER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.secrets", hasSize(2)));

            // Act & Assert: Read metadata of one secret
            performReadSecretMetadata(projectId, "list-secret-1", ADMIN_USER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("list-secret-1"))
                    .andExpect(jsonPath("$.metadataVersion").exists());
        }

        @Test
        void shouldDeleteSecret() throws Exception {
            final String projectId = "project-delete";
            final String secretName = "deletable-secret";
            performCreateSecret(projectId, secretName, "some-value", ADMIN_USER);

            // Act & Assert: Delete the secret
            performDeleteSecret(projectId, secretName, ADMIN_USER)
                    .andExpect(status().isNoContent());

            // Act & Assert: Verify it's gone
            performReadSecretMetadata(projectId, secretName, ADMIN_USER)
                    .andExpect(status().isNotFound());
        }

        @Test
        void shouldHandlePaginationCorrectly() throws Exception {
            final String projectId = "project-pagination";
            performCreateSecret(projectId, "secret-1", "v1", ADMIN_USER);
            performCreateSecret(projectId, "secret-2", "v2", ADMIN_USER);
            performCreateSecret(projectId, "secret-3", "v3", ADMIN_USER);

            // Act & Assert: Test limit
            performListSecrets(projectId, ADMIN_USER, "limit=2")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.secrets", hasSize(2)));

            // Act & Assert: Test offset (Spring PageRequest uses page number, not item offset)
            performListSecrets(projectId, ADMIN_USER, "limit=2", "offset=1")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.secrets", hasSize(1)))
                    .andExpect(jsonPath("$.secrets[0].name").value("secret-3"));

            // Act & Assert: Test empty project
            performListSecrets("empty-project", ADMIN_USER)
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

            performReadSecretMetadata(projectId, secretName, ADMIN_USER).andExpect(status().isNotFound());
            performReadSecretValue(projectId, secretName, ADMIN_USER).andExpect(status().isNotFound());
            performUpgradeSecret(projectId, secretName, "some-value", ADMIN_USER).andExpect(status().isNotFound());
        }

        @Test
        void shouldReturnConflictWhenCreatingDuplicateSecret() throws Exception {
            final String projectId = "project-conflict";
            final String secretName = "duplicate-secret";
            performCreateSecret(projectId, secretName, "value1", ADMIN_USER);

            // Act & Assert: Try to create it again
            performCreateSecret(projectId, secretName, "value2", ADMIN_USER)
                    .andExpect(status().isConflict());
        }

        @Test
        void shouldReturnBadRequestForInvalidInput() throws Exception {
            // Act & Assert: Test with a blank projectId
            performListSecrets(" ", ADMIN_USER)
                    .andExpect(status().isBadRequest());

            // Act & Assert: Test with invalid limit
            performListSecrets("some-project", ADMIN_USER, "limit=101")
                    .andExpect(status().isBadRequest());

            // Act & Assert: Test create with blank name
            performCreateSecret("some-project", " ", "value", ADMIN_USER)
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    class AuthorizationTests {

        @Test
        void viewerCanListAndReadMetadataButCannotModify() throws Exception {
            final String secretName = "auth-secret-viewer";
            performCreateSecret(TEST_PROJECT, secretName, "value", ADMIN_USER);

            // Allowed actions
            performListSecrets(TEST_PROJECT, VIEWER_USER).andExpect(status().isOk());
            performReadSecretMetadata(TEST_PROJECT, secretName, VIEWER_USER).andExpect(status().isOk());

            // Forbidden actions
            performReadSecretValue(TEST_PROJECT, secretName, VIEWER_USER).andExpect(status().isForbidden());
            performUpgradeSecret(TEST_PROJECT, secretName, "new-value", VIEWER_USER).andExpect(status().isForbidden());
            performCreateSecret(TEST_PROJECT, "new-secret", "value", VIEWER_USER).andExpect(status().isForbidden());
            performDeleteSecret(TEST_PROJECT, secretName, VIEWER_USER).andExpect(status().isForbidden());
        }

        @Test
        void editorCanPerformAllStandardActions() throws Exception {
            final String secretName = "auth-secret-editor";

            // Allowed actions
            performCreateSecret(TEST_PROJECT, secretName, "value", EDITOR_USER).andExpect(status().isOk());
            performListSecrets(TEST_PROJECT, EDITOR_USER).andExpect(status().isOk());
            performReadSecretMetadata(TEST_PROJECT, secretName, EDITOR_USER).andExpect(status().isOk());
            performReadSecretValue(TEST_PROJECT, secretName, EDITOR_USER).andExpect(status().isOk());
            performUpgradeSecret(TEST_PROJECT, secretName, "new-value", EDITOR_USER).andExpect(status().isOk());
            performDeleteSecret(TEST_PROJECT, secretName, EDITOR_USER).andExpect(status().isNoContent());
        }

        @Test
        void userIsRestrictedToTheirAssignedProject() throws Exception {
            final String secretName = "other-project-secret";
            performCreateSecret(OTHER_PROJECT, secretName, "value", ADMIN_USER);

            // Forbidden actions for editor/viewer on a project they don't have access to
            performListSecrets(OTHER_PROJECT, EDITOR_USER).andExpect(status().isForbidden());
            performListSecrets(OTHER_PROJECT, VIEWER_USER).andExpect(status().isForbidden());
            performReadSecretMetadata(OTHER_PROJECT, secretName, VIEWER_USER).andExpect(status().isForbidden());
        }
    }
} 