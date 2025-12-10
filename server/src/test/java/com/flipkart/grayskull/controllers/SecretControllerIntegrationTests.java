package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.BaseIntegrationTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static com.flipkart.grayskull.controllers.GrayskullUserRequestPostProcessor.user;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the Secret and Admin controllers.
 * These tests cover the full application lifecycle including web layer, service layer,
 * and database interactions using a real, ephemeral MongoDB database via Testcontainers.
 */
class SecretControllerIntegrationTests extends BaseIntegrationTest {

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
                    .andExpect(jsonPath("$.data.name").value(secretName))
                    .andExpect(jsonPath("$.data.currentDataVersion").value(1))
                    .andExpect(jsonPath("$.message").value("Successfully created secret."));

            // Act & Assert: Read the secret's value
            performReadSecretValue(projectId, secretName, ADMIN_USER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.privatePart").value(secretValue))
                    .andExpect(jsonPath("$.message").value("Successfully read secret value."));
        }

        @Test
        void shouldCreateAndReadSecretWithNullPublicPart() throws Exception {
            final String projectId = "project-create-read";
            final String secretName = "public-null-secret";
            final String secretValue = "my-secret-value";

            // Act & Assert: Create the secret
            performCreateSecret(projectId, secretName, null, secretValue, ADMIN_USER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value(secretName))
                    .andExpect(jsonPath("$.data.currentDataVersion").value(1))
                    .andExpect(jsonPath("$.message").value("Successfully created secret."));

            // Act & Assert: Read the secret's value
            performReadSecretValue(projectId, secretName, ADMIN_USER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.privatePart").value(secretValue))
                    .andExpect(jsonPath("$.data.publicPart").doesNotExist())
                    .andExpect(jsonPath("$.message").value("Successfully read secret value."));
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
                    .andExpect(jsonPath("$.data.dataVersion").value(2))
                    .andExpect(jsonPath("$.message").value("Successfully upgraded secret data."));

            // Act & Assert: Verify the new value is active
            performReadSecretValue(projectId, secretName, ADMIN_USER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.privatePart").value(upgradedValue));
        }

        @Test
        void shouldListSecretsAndReadMetadata() throws Exception {
            final String projectId = "project-list-meta";
            performCreateSecret(projectId, "list-secret-1", "v1", ADMIN_USER);
            performCreateSecret(projectId, "list-secret-2", "v2", ADMIN_USER);

            // Act & Assert: List secrets
            performListSecrets(projectId, ADMIN_USER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.secrets", hasSize(2)))
                    .andExpect(jsonPath("$.message").value("Successfully listed secrets."));

            // Act & Assert: Read metadata of one secret
            performReadSecretMetadata(projectId, "list-secret-1", ADMIN_USER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("list-secret-1"))
                    .andExpect(jsonPath("$.data.metadataVersion").exists())
                    .andExpect(jsonPath("$.message").value("Successfully read secret metadata."));
        }

        @Test
        void shouldDeleteSecret() throws Exception {
            final String projectId = "project-delete";
            final String secretName = "deletable-secret";
            performCreateSecret(projectId, secretName, "some-value", ADMIN_USER);

            // Act & Assert: Delete the secret
            performDeleteSecret(projectId, secretName, ADMIN_USER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Successfully deleted secret."));

            // Act & Assert: Verify it's gone from public APIs
            performReadSecretMetadata(projectId, secretName, ADMIN_USER)
                    .andExpect(status().isNotFound());

            // Act & Assert: Verify it can be fetched by an admin by specifying state
            mockMvc.perform(get(String.format("/v1/projects/%s/secrets/%s/versions/1?state=DISABLED", projectId, secretName))
                            .with(user(ADMIN_USER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.state").value("DISABLED"));
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
                    .andExpect(jsonPath("$.data.secrets", hasSize(2)));

            // Act & Assert: Test offset (offset=1 means skip first record, return next 2)
            performListSecrets(projectId, ADMIN_USER, "limit=2", "offset=1")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.secrets", hasSize(2)))
                    .andExpect(jsonPath("$.data.secrets[0].name").value("secret-2"))
                    .andExpect(jsonPath("$.data.secrets[1].name").value("secret-3"));

            // Act & Assert: Test empty project
            performListSecrets("empty-project", ADMIN_USER)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.secrets", hasSize(0)));
        }

        @Test
        void shouldReturnEmptyListForOutOfBoundsOffset() throws Exception {
            final String projectId = "project-pagination-offset";
            performCreateSecret(projectId, "secret-1", "v1", ADMIN_USER);

            // Act & Assert: Query with an offset equal to the total number of items
            performListSecrets(projectId, ADMIN_USER, "offset=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.secrets", hasSize(0)));
        }

        @Test
        void shouldHandleComplexPaginationScenarios() throws Exception {
            final String projectId = "project-pagination-complex";
            
            // Create 10 secrets
            for (int i = 1; i <= 10; i++) {
                performCreateSecret(projectId, "secret-" + i, "value-" + i, ADMIN_USER);
            }

            // Test 1: First page with limit 3
            performListSecrets(projectId, ADMIN_USER, "limit=3", "offset=0")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.secrets", hasSize(3)))
                    .andExpect(jsonPath("$.data.total").value(10))
                    .andExpect(jsonPath("$.data.secrets[0].name").value("secret-1"))
                    .andExpect(jsonPath("$.data.secrets[1].name").value("secret-2"))
                    .andExpect(jsonPath("$.data.secrets[2].name").value("secret-3"));

            // Test 2: Second page with limit 3 (offset=3)
            performListSecrets(projectId, ADMIN_USER, "limit=3", "offset=3")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.secrets", hasSize(3)))
                    .andExpect(jsonPath("$.data.total").value(10))
                    .andExpect(jsonPath("$.data.secrets[0].name").value("secret-4"))
                    .andExpect(jsonPath("$.data.secrets[1].name").value("secret-5"))
                    .andExpect(jsonPath("$.data.secrets[2].name").value("secret-6"));

            // Test 3: Non-aligned offset (offset=5, limit=3)
            performListSecrets(projectId, ADMIN_USER, "limit=3", "offset=5")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.secrets", hasSize(3)))
                    .andExpect(jsonPath("$.data.total").value(10))
                    .andExpect(jsonPath("$.data.secrets[0].name").value("secret-6"))
                    .andExpect(jsonPath("$.data.secrets[1].name").value("secret-7"))
                    .andExpect(jsonPath("$.data.secrets[2].name").value("secret-8"));

            // Test 4: Last partial page (offset=8, limit=5) - should return 2 items
            performListSecrets(projectId, ADMIN_USER, "limit=5", "offset=8")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.secrets", hasSize(2)))
                    .andExpect(jsonPath("$.data.total").value(10))
                    .andExpect(jsonPath("$.data.secrets[0].name").value("secret-9"))
                    .andExpect(jsonPath("$.data.secrets[1].name").value("secret-10"));

            // Test 5: Exact boundary (offset=9, limit=1) - last item
            performListSecrets(projectId, ADMIN_USER, "limit=1", "offset=9")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.secrets", hasSize(1)))
                    .andExpect(jsonPath("$.data.total").value(10))
                    .andExpect(jsonPath("$.data.secrets[0].name").value("secret-10"));

            // Test 6: Beyond total count
            performListSecrets(projectId, ADMIN_USER, "limit=5", "offset=10")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.secrets", hasSize(0)))
                    .andExpect(jsonPath("$.data.total").value(10));

            // Test 7: Large offset
            performListSecrets(projectId, ADMIN_USER, "limit=5", "offset=100")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.secrets", hasSize(0)))
                    .andExpect(jsonPath("$.data.total").value(10));
        }
    }

    /**
     * Tests covering expected failure scenarios, such as invalid input or resource conflicts.
     */
    @Nested
    class FailurePathTests {

        @Test
        void shouldReturnForbiddenWithoutCredentials() throws Exception {
            mockMvc.perform(get("/v1/projects/some-project/secrets"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void shouldReturnForbiddenForNonExistentOperations() throws Exception {
            final String projectId = "project-not-found";
            final String secretName = "non-existent-secret";

            performReadSecretMetadata(projectId, secretName, ADMIN_USER)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

            performReadSecretValue(projectId, secretName, ADMIN_USER)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

            performUpgradeSecret(projectId, secretName, "some-value", ADMIN_USER)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        @Test
        void shouldReturnConflictWhenCreatingDuplicateSecret() throws Exception {
            final String projectId = "project-conflict";
            final String secretName = "duplicate-secret";
            performCreateSecret(projectId, secretName, "value1", ADMIN_USER);

            // Act & Assert: Try to create it again while it's active
            performCreateSecret(projectId, secretName, "value2", ADMIN_USER)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT"));

            // Act & Assert: Soft-delete the secret and try to create it again
            performDeleteSecret(projectId, secretName, ADMIN_USER).andExpect(status().isOk());
            performCreateSecret(projectId, secretName, "value3", ADMIN_USER)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT"));
        }

        @Test
        void shouldReturnBadRequestForInvalidInput() throws Exception {
            // Act & Assert: Test with a blank projectId
            performListSecrets(" ", ADMIN_USER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

            // Act & Assert: Test with invalid limit
            performListSecrets("some-project", ADMIN_USER, "limit=101")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

            // Act & Assert: Test create with blank name
            performCreateSecret("some-project", " ", "value", ADMIN_USER)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }

        @Test
        void shouldReturnBadRequestForInvalidUpgradeRequest() throws Exception {
            final String projectId = "project-invalid-upgrade";
            final String secretName = "secret-to-upgrade";
            performCreateSecret(projectId, secretName, "initial-value", ADMIN_USER);

            // Act & Assert: Attempt to upgrade with a blank privatePart
            performUpgradeSecret(projectId, secretName, " ", ADMIN_USER)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }

        @Test
        void shouldReturnNotFoundWhenUpgradingDisabledSecret() throws Exception {
            final String projectId = "project-upgrade-disabled";
            final String secretName = "secret-to-upgrade-disabled";
            performCreateSecret(projectId, secretName, "initial-value", ADMIN_USER);
            performDeleteSecret(projectId, secretName, ADMIN_USER).andExpect(status().isOk());

            // Act & Assert: Attempt to upgrade a disabled secret
            performUpgradeSecret(projectId, secretName, "new-value", ADMIN_USER)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        void shouldReturnNotFoundWhenDeletingAlreadyDisabledSecret() throws Exception {
            final String projectId = "project-delete-disabled";
            final String secretName = "secret-to-delete-disabled";
            performCreateSecret(projectId, secretName, "some-value", ADMIN_USER);
            performDeleteSecret(projectId, secretName, ADMIN_USER).andExpect(status().isOk());

            // Act & Assert: Attempt to delete it again
            performDeleteSecret(projectId, secretName, ADMIN_USER)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        void shouldReturnForbiddenWhenDeletingNonExistentSecret() throws Exception {
            // Act & Assert: Attempt to delete a secret that was never created
            performDeleteSecret("project-delete-non-existent", "non-existent-secret", ADMIN_USER)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
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
            performReadSecretValue(TEST_PROJECT, secretName, VIEWER_USER).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            performUpgradeSecret(TEST_PROJECT, secretName, "new-value", VIEWER_USER).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            performCreateSecret(TEST_PROJECT, "new-secret", "value", VIEWER_USER).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            performDeleteSecret(TEST_PROJECT, secretName, VIEWER_USER).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
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
            performDeleteSecret(TEST_PROJECT, secretName, EDITOR_USER).andExpect(status().isOk());
        }

        @Test
        void userIsRestrictedToTheirAssignedProject() throws Exception {
            final String secretName = "other-project-secret";
            performCreateSecret(OTHER_PROJECT, secretName, "value", ADMIN_USER);

            // Forbidden actions for editor/viewer on a project they don't have access to
            performListSecrets(OTHER_PROJECT, EDITOR_USER).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            performListSecrets(OTHER_PROJECT, VIEWER_USER).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            performReadSecretMetadata(OTHER_PROJECT, secretName, VIEWER_USER).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Nested
    class AdminActionsTests {
        @Test
        void shouldGetSpecificSecretVersion() throws Exception {
            final String projectId = "project-admin-version";
            final String secretName = "versioned-secret";
            performCreateSecret(projectId, secretName, "value-v1", ADMIN_USER);
            performUpgradeSecret(projectId, secretName, "value-v2", ADMIN_USER);

            // Act & Assert: Use the admin endpoint to get the first version
            mockMvc.perform(get(String.format("/v1/projects/%s/secrets/%s/versions/1", projectId, secretName))
                .with(user(ADMIN_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.privatePart").value("value-v1"))
                .andExpect(jsonPath("$.data.dataVersion").value(1))
                .andExpect(jsonPath("$.message").value("Successfully retrieved secret version."));

            // Act & Assert: Get the second version
            mockMvc.perform(get(String.format("/v1/projects/%s/secrets/%s/versions/2", projectId, secretName))
                            .with(user(ADMIN_USER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.privatePart").value("value-v2"));
        }

        @Test
        void shouldGetSpecificSecretVersionWithActiveState() throws Exception {
            final String projectId = "project-admin-active";
            final String secretName = "active-secret";
            performCreateSecret(projectId, secretName, "value-v1", ADMIN_USER);

            // Act & Assert: Get a version of an active secret by specifying state=ACTIVE
            mockMvc.perform(get(String.format("/v1/projects/%s/secrets/%s/versions/1?state=ACTIVE", projectId, secretName))
                            .with(user(ADMIN_USER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.privatePart").value("value-v1"))
                    .andExpect(jsonPath("$.data.state").value("ACTIVE"));

            // Act & Assert: Fail to get a version of an active secret when specifying wrong state
            mockMvc.perform(get(String.format("/v1/projects/%s/secrets/%s/versions/1?state=DISABLED", projectId, secretName))
                            .with(user(ADMIN_USER)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void shouldGetSpecificSecretVersionOfDisabledSecret() throws Exception {
            final String projectId = "project-admin-disabled";
            final String secretName = "disabled-secret";
            performCreateSecret(projectId, secretName, "value-v1", ADMIN_USER);
            performDeleteSecret(projectId, secretName, ADMIN_USER).andExpect(status().isOk());

            // Act & Assert: Get a version of a disabled secret without specifying state (should fail)
            mockMvc.perform(get(String.format("/v1/projects/%s/secrets/%s/versions/1", projectId, secretName))
                            .with(user(ADMIN_USER)))
                    .andExpect(status().isNotFound());

            // Act & Assert: Get a version of a disabled secret by specifying state
            mockMvc.perform(get(String.format("/v1/projects/%s/secrets/%s/versions/1?state=DISABLED", projectId, secretName))
                            .with(user(ADMIN_USER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.privatePart").value("value-v1"))
                    .andExpect(jsonPath("$.data.state").value("DISABLED"));

            // Act & Assert: Fail to get a version of a disabled secret when specifying wrong state
            mockMvc.perform(get(String.format("/v1/projects/%s/secrets/%s/versions/1?state=ACTIVE", projectId, secretName))
                            .with(user(ADMIN_USER)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void shouldReturnNotFoundForNonExistentVersion() throws Exception {
            final String projectId = "project-admin-not-found";
            final String secretName = "secret-with-one-version";
            performCreateSecret(projectId, secretName, "v1", ADMIN_USER);

            // Act & Assert: Try to get a version that doesn't exist
            mockMvc.perform(get(String.format("/v1/projects/%s/secrets/%s/versions/99", projectId, secretName))
                .with(user(ADMIN_USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        void nonAdminUsersAreForbiddenFromAdminEndpoints() throws Exception {
            final String secretName = "admin-auth-secret";
            performCreateSecret(TEST_PROJECT, secretName, "v1", ADMIN_USER);
            performUpgradeSecret(TEST_PROJECT, secretName, "v2", ADMIN_USER);

            mockMvc.perform(get(String.format("/v1/projects/%s/secrets/%s/versions/1", TEST_PROJECT, secretName))
                .with(user(EDITOR_USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        @Test
        void shouldReturnBadRequestForInvalidVersionNumber() throws Exception {
            final String projectId = "project-admin-invalid-version";
            final String secretName = "secret-for-invalid-version";
            performCreateSecret(projectId, secretName, "v1", ADMIN_USER);

            // Act & Assert: Try to get version 0, which is invalid
            mockMvc.perform(get(String.format("/v1/projects/%s/secrets/%s/versions/0", projectId, secretName))
                            .with(user(ADMIN_USER)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
    }

    /**
     * Tests covering web-layer exceptions handled by the GlobalExceptionHandler.
     */
    @Nested
    class WebLayerExceptionTests {

        @Test
        void shouldReturnMethodNotAllowedForUnsupportedHttpVerb() throws Exception {
            mockMvc.perform(put(String.format("/v1/projects/%s/secrets/%s/data", TEST_PROJECT, "some-secret"))
                    .with(user(ADMIN_USER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"privatePart\": \"some-value\"}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.violations").doesNotExist());
        }

        @Test
        void shouldReturnUnprocessableEntityForMalformedJson() throws Exception {
            mockMvc.perform(post(String.format("/v1/projects/%s/secrets", TEST_PROJECT))
                    .with(user(ADMIN_USER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\": \"test-secret\", \"privatePart\": }")) // Invalid JSON
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE_ENTITY"))
                .andExpect(jsonPath("$.message").value("Malformed JSON request"))
                .andExpect(jsonPath("$.violations").doesNotExist());
        }


        @Test
        void shouldReturnStructuredViolationForTypeMismatchError() throws Exception {
            // Path variable 'version' expects Integer but receives String
            mockMvc.perform(get(String.format("/v1/projects/%s/secrets/%s/versions/%s", TEST_PROJECT, "some-secret", "not-a-number"))
                    .with(user(ADMIN_USER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.violations").isArray())
                .andExpect(jsonPath("$.violations", hasSize(1)))
                .andExpect(jsonPath("$.violations[0].field").value("version"))
                .andExpect(jsonPath("$.violations[0].message").exists());
        }


        @Test
        void shouldNotIncludeViolationsForNonValidationErrors() throws Exception {
            // Create a project/secret first so user has access to the project
            final String projectId = "project-not-found-test";
            performCreateSecret(projectId, "existing-secret", "some-value", ADMIN_USER);
            
            // Test that 404 errors don't include violations field
            mockMvc.perform(get(String.format("/v1/projects/%s/secrets/%s", projectId, "non-existent-secret"))
                    .with(user(ADMIN_USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.violations").doesNotExist());
        }
    }
} 