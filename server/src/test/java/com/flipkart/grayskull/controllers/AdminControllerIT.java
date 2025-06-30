package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.BaseIntegrationTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the {@link AdminController}.
 */
class AdminControllerIT extends BaseIntegrationTest {

    @Nested
    class HappyPathTests {
        @Test
        void shouldGetSpecificSecretVersion() throws Exception {
            final String projectId = "project-admin-version";
            final String secretName = "versioned-secret";
            performCreateSecret(projectId, secretName, "value-v1");
            performUpgradeSecret(projectId, secretName, "value-v2");

            // Act & Assert: Use the admin endpoint to get the first version
            performGetSecretByVersion(projectId, secretName, 1)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.privatePart").value("value-v1"))
                    .andExpect(jsonPath("$.dataVersion").value(1));
        }
    }

    @Nested
    class FailurePathTests {
        @Test
        void shouldReturnNotFoundForNonExistentVersion() throws Exception {
            final String projectId = "project-admin-not-found";
            final String secretName = "secret-with-one-version";
            performCreateSecret(projectId, secretName, "v1");

            // Act & Assert: Try to get a version that doesn't exist
            performGetSecretByVersion(projectId, secretName, 99)
                    .andExpect(status().isNotFound());
        }
    }
} 