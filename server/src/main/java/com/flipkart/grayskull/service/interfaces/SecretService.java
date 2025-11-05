package com.flipkart.grayskull.service.interfaces;

import com.flipkart.grayskull.models.dto.request.CreateSecretRequest;
import com.flipkart.grayskull.models.dto.request.UpgradeSecretDataRequest;
import com.flipkart.grayskull.models.dto.response.SecretResponse;
import com.flipkart.grayskull.models.dto.response.ListSecretsResponse;
import com.flipkart.grayskull.models.dto.response.SecretDataResponse;
import com.flipkart.grayskull.models.dto.response.SecretDataVersionResponse;
import com.flipkart.grayskull.models.dto.response.SecretMetadata;
import com.flipkart.grayskull.models.dto.response.UpgradeSecretDataResponse;
import com.flipkart.grayskull.spi.models.enums.LifecycleState;

import java.util.Optional;

public interface SecretService {

    /**
     * Lists secrets for a given project with pagination. Always returns the latest
     * version of the secret.
     * 
     * @param projectId The ID of the project.
     * @param offset    The starting offset for pagination.
     * @param limit     The maximum number of secrets to return.
     * @return A {@link ListSecretsResponse} containing the list of secret metadata
     *         and the total count.
     */
    ListSecretsResponse listSecrets(String projectId, int offset, int limit);

    /**
     * Creates a new secret for a given project.
     * 
     * @param projectId The ID of the project.
     * @param request   The request body containing the secret details.
     * @return A {@link SecretResponse} containing the details of the created
     *         secret.
     */
    SecretResponse createSecret(String projectId, CreateSecretRequest request);

    /**
     * Reads the metadata of a specific secret. Always returns the latest version of
     * the secret.
     * 
     * @param projectId  The ID of the project.
     * @param secretName The name of the secret.
     * @return {@link SecretMetadata} for the requested secret.
     */
    SecretMetadata readSecretMetadata(String projectId, String secretName);

    /**
     * Reads the value of a specific secret. Always returns the latest version of
     * the secret.
     * 
     * @param projectId  The ID of the project.
     * @param secretName The name of the secret.
     * @return A {@link SecretDataResponse} containing the secret's value.
     */
    SecretDataResponse readSecretValue(String projectId, String secretName);

    /**
     * Upgrades the data of an existing secret, creating a new version.
     * 
     * @param projectId  The ID of the project.
     * @param secretName The name of the secret to upgrade.
     * @param request    The request containing the new secret data.
     * @return An {@link UpgradeSecretDataResponse} with the new data version.
     */
    UpgradeSecretDataResponse upgradeSecretData(String projectId, String secretName, UpgradeSecretDataRequest request);

    /**
     * Disables a secret, marking it as soft-deleted.
     * 
     * @param projectId  The ID of the project.
     * @param secretName The name of the secret to delete.
     */
    void deleteSecret(String projectId, String secretName);

    /**
     * Retrieves a specific version of a secret's data. Its an Admin API.
     * 
     * @param projectId  The ID of the project.
     * @param secretName The name of the secret.
     * @param version    The version of the secret data to retrieve.
     * @param state      Optional state of secret
     * @return A {@link SecretDataVersionResponse} containing the secret data for
     *         the specified version.
     */
    SecretDataVersionResponse getSecretDataVersion(String projectId, String secretName, int version,
            Optional<LifecycleState> state);
}