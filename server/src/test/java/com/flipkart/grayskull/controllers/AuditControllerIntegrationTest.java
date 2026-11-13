package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.BaseIntegrationTest;
import com.flipkart.grayskull.audit.AuditAction;
import com.flipkart.grayskull.audit.UserType;
import com.flipkart.grayskull.audit.relay.AuditOutboxRelayer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.flipkart.grayskull.controllers.GrayskullUserRequestPostProcessor.user;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for AuditController.
 * These tests cover the full application lifecycle including web layer, service layer,
 * and database interactions using real, ephemeral MongoDB and MySQL databases via Testcontainers.
 * Write audits reach MySQL through the outbox, so every secret write here is followed by a relayer run.
 */
@DisplayName("AuditController Integration Tests")
class AuditControllerIntegrationTest extends BaseIntegrationTest {

    private static final String ADMIN_USER = "admin";
    private static final String SERVICE_USER = "service:automated-task";
    private static final String HUMAN_USER = "human:john.doe";
    private static final String TEST_PROJECT = "audit-test-project";

    @Autowired
    private AuditOutboxRelayer relayer;

    @Override
    protected ResultActions performCreateSecret(String projectId, String secretName, String publicPart, String secretValue, String username) throws Exception {
        ResultActions result = super.performCreateSecret(projectId, secretName, publicPart, secretValue, username);
        relayer.tick();
        return result;
    }

    @Override
    protected ResultActions performUpgradeSecret(String projectId, String secretName, String newSecretValue, String username) throws Exception {
        ResultActions result = super.performUpgradeSecret(projectId, secretName, newSecretValue, username);
        relayer.tick();
        return result;
    }

    // TODO re-enable once the db/audit Flyway migrations are pushed; until then the audit tables don't exist
    @Disabled("needs the db/audit migrations")
    @Nested
    @DisplayName("Project-Specific Audit Tests")
    class ProjectAuditTests {

        @Test
        @DisplayName("Should retrieve audit entries for a specific project after creating secrets")
        void shouldRetrieveProjectAuditEntries() throws Exception {
            final String targetProject = "project-with-audits";
            final String otherProject = "other-project";
            final String secretName = "audited-secret";

            // Create secrets in DIFFERENT projects to test filtering
            performCreateSecret(targetProject, secretName, "secret-value", ADMIN_USER)
                    .andExpect(status().isOk());
            performCreateSecret(otherProject, "other-secret", "other-value", ADMIN_USER)
                    .andExpect(status().isOk());

            // Query audit entries for the TARGET project only
            mockMvc.perform(get("/v1/audit/projects/{projectId}", targetProject)
                            .with(user(ADMIN_USER))
                            .param("offset", "0")
                            .param("limit", "10"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.data.entries", hasSize(greaterThan(0))))
                    .andExpect(jsonPath("$.data.total", greaterThan(0)))
                    .andExpect(jsonPath("$.data.entries[*].projectId", everyItem(is(targetProject))))
                    .andExpect(jsonPath("$.message", is("Successfully retrieved audit entries.")));
        }

        @Test
        @DisplayName("Should filter audit entries by resource name")
        void shouldFilterByResourceName() throws Exception {
            final String projectId = "project-filter-resource";
            final String secret1 = "filter-secret-1";
            final String secret2 = "filter-secret-2";

            // Create two secrets
            performCreateSecret(projectId, secret1, "value1", ADMIN_USER).andExpect(status().isOk());
            performCreateSecret(projectId, secret2, "value2", ADMIN_USER).andExpect(status().isOk());

            // Query by specific resource name
            mockMvc.perform(get("/v1/audit/projects/{projectId}", projectId)
                            .with(user(ADMIN_USER))
                            .param("resourceName", secret1)
                            .param("limit", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries[*].resourceName", everyItem(is(secret1))));
        }

        @Test
        @DisplayName("Should filter audit entries by resource type")
        void shouldFilterByResourceType() throws Exception {
            final String projectId = "project-filter-type";
            final String secretName = "typed-secret";

            performCreateSecret(projectId, secretName, "value", ADMIN_USER).andExpect(status().isOk());

            mockMvc.perform(get("/v1/audit/projects/{projectId}", projectId)
                            .with(user(ADMIN_USER))
                            .param("resourceType", "SECRET")
                            .param("limit", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries[*].resourceType", everyItem(is("SECRET"))));
        }

        @Test
        @DisplayName("Should filter audit entries by action")
        void shouldFilterByAction() throws Exception {
            final String projectId = "project-filter-action";
            final String secretName = "action-secret";

            // Perform MULTIPLE different actions to test filtering
            performCreateSecret(projectId, secretName, "value", ADMIN_USER).andExpect(status().isOk());
            performReadSecretValue(projectId, secretName, ADMIN_USER).andExpect(status().isOk());
            performUpgradeSecret(projectId, secretName, "new-value", ADMIN_USER).andExpect(status().isOk());

            // Query with CREATE_SECRET filter - should only return create actions
            mockMvc.perform(get("/v1/audit/projects/{projectId}", projectId)
                            .with(user(ADMIN_USER))
                            .param("action", AuditAction.CREATE_SECRET.name())
                            .param("limit", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries", hasSize(greaterThan(0))))
                    // Verify ALL entries are CREATE_SECRET (not READ or UPDATE)
                    .andExpect(jsonPath("$.data.entries[*].action", everyItem(is(AuditAction.CREATE_SECRET.name()))));
        }

        @Test
        @DisplayName("Should filter audit entries by user type - SERVICE")
        void shouldFilterByServiceUserType() throws Exception {
            final String projectId = "project-service-user";

            // Create secrets with BOTH service and human users to test filtering
            performCreateSecret(projectId, "service-secret", "value", SERVICE_USER).andExpect(status().isOk());
            performCreateSecret(projectId, "human-secret", "value", HUMAN_USER).andExpect(status().isOk());

            // Query with SERVICE filter - should only return service user entries
            mockMvc.perform(get("/v1/audit/projects/{projectId}", projectId)
                            .with(user(ADMIN_USER))
                            .param("userType", UserType.SERVICE.name())
                            .param("limit", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries", hasSize(greaterThan(0))))
                    // Verify ALL entries have service: prefix (not human:)
                    .andExpect(jsonPath("$.data.entries[*].userId", everyItem(startsWith("service:"))));
        }

        @Test
        @DisplayName("Should filter audit entries by user type - HUMAN")
        void shouldFilterByHumanUserType() throws Exception {
            final String projectId = "project-human-user";

            // Create secrets with BOTH service and human users to test filtering
            performCreateSecret(projectId, "service-secret", "value", SERVICE_USER).andExpect(status().isOk());
            performCreateSecret(projectId, "human-secret", "value", HUMAN_USER).andExpect(status().isOk());

            // Query with HUMAN filter - should only return human user entries
            mockMvc.perform(get("/v1/audit/projects/{projectId}", projectId)
                            .with(user(ADMIN_USER))
                            .param("userType", UserType.HUMAN.name())
                            .param("limit", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries", hasSize(greaterThan(0))))
                    // Verify ALL entries have human: prefix (not service:)
                    .andExpect(jsonPath("$.data.entries[*].userId", everyItem(startsWith("human:"))));
        }

        @Test
        @DisplayName("Should filter audit entries by timestamp")
        void shouldFilterByTimestamp() throws Exception {
            final String projectId = "project-timestamp-filter";
            final String secretName = "timestamped-secret";

            Instant beforeCreation = Instant.now();
            performCreateSecret(projectId, secretName, "value", ADMIN_USER).andExpect(status().isOk());

            String afterTimestamp = beforeCreation.toString();
            mockMvc.perform(get("/v1/audit/projects/{projectId}", projectId)
                            .with(user(ADMIN_USER))
                            .param("afterTimestamp", afterTimestamp)
                            .param("limit", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries", hasSize(greaterThan(0))));
        }

        @Test
        @DisplayName("Should handle pagination correctly")
        void shouldHandlePagination() throws Exception {
            final String projectId = "pagination-test-audits";

            // Create multiple secrets to generate multiple audit entries
            for (int i = 1; i <= 5; i++) {
                performCreateSecret(projectId, "secret-" + i, "value-" + i, ADMIN_USER)
                        .andExpect(status().isOk());
            }

            // Test first page
            mockMvc.perform(get("/v1/audit/projects/{projectId}", projectId)
                            .with(user(ADMIN_USER))
                            .param("offset", "0")
                            .param("limit", "3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries", hasSize(3)))
                    .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(5)));

            // Test second page
            mockMvc.perform(get("/v1/audit/projects/{projectId}", projectId)
                            .with(user(ADMIN_USER))
                            .param("offset", "3")
                            .param("limit", "3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries", hasSize(greaterThanOrEqualTo(2))));
        }

        @Test
        @DisplayName("Should return empty list when no audit entries match filters")
        void shouldReturnEmptyListWhenNoMatch() throws Exception {
            final String projectId = "project-no-match";

            mockMvc.perform(get("/v1/audit/projects/{projectId}", projectId)
                            .with(user(ADMIN_USER))
                            .param("resourceName", "non-existent-secret")
                            .param("limit", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries", hasSize(0)))
                    .andExpect(jsonPath("$.data.total", is(0)));
        }

        @Test
        @DisplayName("Should reject queries older than maximum allowed age")
        void shouldRejectOldTimestampQueries() throws Exception {
            final String projectId = "project-old-query";

            // Try to query with a timestamp older than 180 days (default max age)
            String oldTimestamp = Instant.now().minus(200, ChronoUnit.DAYS).toString();

            mockMvc.perform(get("/v1/audit/projects/{projectId}", projectId)
                            .with(user(ADMIN_USER))
                            .param("afterTimestamp", oldTimestamp))
                    .andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("Should apply default timestamp when none provided")
        void shouldApplyDefaultTimestamp() throws Exception {
            final String projectId = "project-default-timestamp";
            final String secretName = "default-timestamp-secret";

            performCreateSecret(projectId, secretName, "value", ADMIN_USER).andExpect(status().isOk());

            // Query without timestamp should still work (uses default)
            mockMvc.perform(get("/v1/audit/projects/{projectId}", projectId)
                            .with(user(ADMIN_USER))
                            .param("limit", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries", hasSize(greaterThan(0))));
        }

        @Test
        @DisplayName("Should combine multiple filters correctly")
        void shouldCombineMultipleFilters() throws Exception {
            final String projectId = "project-multi-filter";
            final String humanSecretName = "human-secret";
            final String serviceSecretName = "service-secret";

            // Create secrets with DIFFERENT users and perform DIFFERENT actions
            performCreateSecret(projectId, humanSecretName, "value", HUMAN_USER).andExpect(status().isOk());
            performReadSecretValue(projectId, humanSecretName, HUMAN_USER).andExpect(status().isOk());
            performCreateSecret(projectId, serviceSecretName, "value", SERVICE_USER).andExpect(status().isOk());
            performReadSecretValue(projectId, serviceSecretName, SERVICE_USER).andExpect(status().isOk());

            // Query with multiple filters: resourceName + resourceType + action + userType
            mockMvc.perform(get("/v1/audit/projects/{projectId}", projectId)
                            .with(user(ADMIN_USER))
                            .param("resourceName", humanSecretName)
                            .param("resourceType", "SECRET")
                            .param("action", AuditAction.CREATE_SECRET.name())
                            .param("userType", UserType.HUMAN.name())
                            .param("limit", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries", hasSize(1))) // Should only match one entry
                    // Verify all filters are applied correctly
                    .andExpect(jsonPath("$.data.entries[0].resourceName", is(humanSecretName)))
                    .andExpect(jsonPath("$.data.entries[0].resourceType", is("SECRET")))
                    .andExpect(jsonPath("$.data.entries[0].action", is(AuditAction.CREATE_SECRET.name())))
                    .andExpect(jsonPath("$.data.entries[0].userId", startsWith("human:")));
        }
    }

    // TODO re-enable once the db/audit Flyway migrations are pushed; until then the audit tables don't exist
    @Disabled("needs the db/audit migrations")
    @Nested
    @DisplayName("Global Audit Tests")
    class GlobalAuditTests {

        @Test
        @DisplayName("Should retrieve audit entries across all projects")
        void shouldRetrieveAllAuditEntries() throws Exception {
            // Create secrets in different projects
            performCreateSecret("global-project-1", "secret-1", "value", ADMIN_USER).andExpect(status().isOk());
            performCreateSecret("global-project-2", "secret-2", "value", ADMIN_USER).andExpect(status().isOk());

            mockMvc.perform(get("/v1/audit")
                            .with(user(ADMIN_USER))
                            .param("limit", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries", hasSize(greaterThan(0))))
                    .andExpect(jsonPath("$.data.total", greaterThan(0)));
        }

        @Test
        @DisplayName("Should filter global audits by projectId")
        void shouldFilterGlobalAuditsByProject() throws Exception {
            final String targetProject = "global-filter-project";
            performCreateSecret(targetProject, "secret", "value", ADMIN_USER).andExpect(status().isOk());

            mockMvc.perform(get("/v1/audit")
                            .with(user(ADMIN_USER))
                            .param("projectId", targetProject)
                            .param("limit", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries[*].projectId", everyItem(is(targetProject))));
        }

        @Test
        @DisplayName("Should filter global audits by resource name")
        void shouldFilterGlobalAuditsByResourceName() throws Exception {
            final String resourceName = "global-resource";
            performCreateSecret("some-project", resourceName, "value", ADMIN_USER).andExpect(status().isOk());

            mockMvc.perform(get("/v1/audit")
                            .with(user(ADMIN_USER))
                            .param("resourceName", resourceName)
                            .param("limit", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries[*].resourceName", everyItem(is(resourceName))));
        }

        @Test
        @DisplayName("Should filter global audits by action")
        void shouldFilterGlobalAuditsByAction() throws Exception {
            performCreateSecret("action-project", "action-secret", "value", ADMIN_USER).andExpect(status().isOk());

            mockMvc.perform(get("/v1/audit")
                            .with(user(ADMIN_USER))
                            .param("action", AuditAction.CREATE_SECRET.name())
                            .param("limit", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries[*].action", everyItem(is(AuditAction.CREATE_SECRET.name()))));
        }

        @Test
        @DisplayName("Should filter global audits by user type")
        void shouldFilterGlobalAuditsByUserType() throws Exception {
            performCreateSecret("service-project", "service-secret", "value", SERVICE_USER).andExpect(status().isOk());

            mockMvc.perform(get("/v1/audit")
                            .with(user(ADMIN_USER))
                            .param("userType", UserType.SERVICE.name())
                            .param("limit", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries[*].userId", everyItem(startsWith("service:"))));
        }

        @Test
        @DisplayName("Should filter global audits by timestamp")
        void shouldFilterGlobalAuditsByTimestamp() throws Exception {
            Instant beforeCreation = Instant.now();
            performCreateSecret("timestamp-project", "timestamp-secret", "value", ADMIN_USER).andExpect(status().isOk());

            String afterTimestamp = beforeCreation.toString();
            mockMvc.perform(get("/v1/audit")
                            .with(user(ADMIN_USER))
                            .param("afterTimestamp", afterTimestamp)
                            .param("limit", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries", hasSize(greaterThan(0))));
        }

        @Test
        @DisplayName("Should handle pagination in global audits")
        void shouldHandleGlobalAuditPagination() throws Exception {
            // Create multiple secrets across projects
            for (int i = 1; i <= 3; i++) {
                performCreateSecret("pagination-project-" + i, "secret-" + i, "value", ADMIN_USER)
                        .andExpect(status().isOk());
            }

            mockMvc.perform(get("/v1/audit")
                            .with(user(ADMIN_USER))
                            .param("offset", "0")
                            .param("limit", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries", hasSize(greaterThan(0))));
        }

        @Test
        @DisplayName("Should return empty list when no global audits match filters")
        void shouldReturnEmptyListForGlobalAuditsWithNoMatch() throws Exception {
            mockMvc.perform(get("/v1/audit")
                            .with(user(ADMIN_USER))
                            .param("projectId", "non-existent-project-xyz")
                            .param("limit", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries", hasSize(0)))
                    .andExpect(jsonPath("$.data.total", is(0)));
        }

        @Test
        @DisplayName("Should not enforce timestamp limit on global audit endpoint")
        void shouldNotEnforceTimestampLimitOnGlobalEndpoint() throws Exception {
            // Global endpoint allows querying older data without default timestamp
            String oldTimestamp = Instant.now().minus(200, ChronoUnit.DAYS).toString();

            mockMvc.perform(get("/v1/audit")
                            .with(user(ADMIN_USER))
                            .param("afterTimestamp", oldTimestamp)
                            .param("limit", "10"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should combine multiple filters in global audits")
        void shouldCombineMultipleFiltersInGlobalAudits() throws Exception {
            final String projectId = "combined-global-project";
            final String secretName = "combined-secret";

            performCreateSecret(projectId, secretName, "value", HUMAN_USER).andExpect(status().isOk());

            mockMvc.perform(get("/v1/audit")
                            .with(user(ADMIN_USER))
                            .param("projectId", projectId)
                            .param("resourceName", secretName)
                            .param("action", AuditAction.CREATE_SECRET.name())
                            .param("userType", UserType.HUMAN.name())
                            .param("limit", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries[0].projectId", is(projectId)))
                    .andExpect(jsonPath("$.data.entries[0].resourceName", is(secretName)))
                    .andExpect(jsonPath("$.data.entries[0].action", is(AuditAction.CREATE_SECRET.name())))
                    .andExpect(jsonPath("$.data.entries[0].userId", startsWith("human:")));
        }
    }

    @Nested
    @DisplayName("Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("Should validate maximum limit parameter")
        void shouldValidateMaxLimit() throws Exception {
            mockMvc.perform(get("/v1/audit/projects/{projectId}", TEST_PROJECT)
                            .with(user(ADMIN_USER))
                            .param("limit", "101"))
                    .andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("Should validate minimum limit parameter")
        void shouldValidateMinLimit() throws Exception {
            mockMvc.perform(get("/v1/audit/projects/{projectId}", TEST_PROJECT)
                            .with(user(ADMIN_USER))
                            .param("limit", "0"))
                    .andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("Should validate minimum offset parameter")
        void shouldValidateMinOffset() throws Exception {
            mockMvc.perform(get("/v1/audit/projects/{projectId}", TEST_PROJECT)
                            .with(user(ADMIN_USER))
                            .param("offset", "-1"))
                    .andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("Should handle invalid timestamp format")
        void shouldHandleInvalidTimestampFormat() throws Exception {
            mockMvc.perform(get("/v1/audit/projects/{projectId}", TEST_PROJECT)
                            .with(user(ADMIN_USER))
                            .param("afterTimestamp", "invalid-timestamp"))
                    .andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("Should handle invalid action enum")
        void shouldHandleInvalidActionEnum() throws Exception {
            mockMvc.perform(get("/v1/audit/projects/{projectId}", TEST_PROJECT)
                            .with(user(ADMIN_USER))
                            .param("action", "INVALID_ACTION"))
                    .andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("Should handle invalid user type enum")
        void shouldHandleInvalidUserTypeEnum() throws Exception {
            mockMvc.perform(get("/v1/audit/projects/{projectId}", TEST_PROJECT)
                            .with(user(ADMIN_USER))
                            .param("userType", "INVALID_USER_TYPE"))
                    .andExpect(status().is4xxClientError());
        }
    }
}
