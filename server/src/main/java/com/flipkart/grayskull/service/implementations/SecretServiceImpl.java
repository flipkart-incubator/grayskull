package com.flipkart.grayskull.service.implementations;

import com.flipkart.grayskull.models.db.Secret;
import com.flipkart.grayskull.models.db.SecretData;
import com.flipkart.grayskull.models.dto.request.CreateSecretRequest;
import com.flipkart.grayskull.models.dto.request.UpgradeSecretDataRequest;
import com.flipkart.grayskull.models.dto.response.*;
import com.flipkart.grayskull.models.enums.AuditAction;
import com.flipkart.grayskull.mappers.SecretMapper;
import com.flipkart.grayskull.configuration.CryptoConfig;
import com.flipkart.grayskull.repositories.SecretDataRepository;
import com.flipkart.grayskull.repositories.SecretRepository;
import com.flipkart.grayskull.service.utils.SecretEncryptionUtil;
import com.flipkart.grayskull.service.interfaces.SecretService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

import com.flipkart.grayskull.audit.Auditable;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class SecretServiceImpl implements SecretService {

    private static final String SYSTEM_USER = "system";
    private final SecretRepository secretRepository;
    private final SecretDataRepository secretDataRepository;
    private final SecretMapper secretMapper;
    private final SecretEncryptionUtil secretEncryptionUtil;
    private final CryptoConfig cryptoConfig;

    /**
     * Lists secrets for a given project with pagination.
     *
     * @param projectId The ID of the project.
     * @param offset    The starting offset for pagination.
     * @param limit     The maximum number of secrets to return.
     * @return A {@link ListSecretsResponse} containing the list of secret metadata and the total count.
     */
    @Override
    public ListSecretsResponse listSecrets(String projectId, int offset, int limit) {
        Pageable pageable = PageRequest.of(offset, limit);
        List<Secret> secrets = secretRepository.findByProjectId(projectId, pageable);
        long total = secretRepository.countByProjectId(projectId);
        List<SecretMetadata> secretMetadata = secrets.stream()
                .map(secretMapper::secretToSecretMetadata)
                .collect(Collectors.toList());
        return new ListSecretsResponse(secretMetadata, total);
    }

    /**
     * Creates a new secret for a given project.
     *
     * @param projectId The ID of the project.
     * @param request   The request body containing the secret details.
     * @return A {@link CreateSecretResponse} containing the details of the created secret.
     * @throws DuplicateSecretException if a secret with the same name already exists in the project.
     */
    @Override
    @Transactional
    @Auditable(action = AuditAction.CREATE_SECRET)
    public CreateSecretResponse createSecret(String projectId, CreateSecretRequest request) {
        secretRepository.findByProjectIdAndName(projectId, request.getName())
                .ifPresent(s -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "A secret with the same name " + request.getName() + " already exists.");
                });

        String keyId = getKeyIdForProject(projectId);

        Secret secret = secretMapper.requestToSecret(request, projectId, SYSTEM_USER);
        SecretData secretData = secretMapper.requestToSecretData(request, secret.getId());
        secretEncryptionUtil.encryptSecretData(secretData, keyId);
        secret.setData(secretData);

        Secret savedSecret = secretRepository.save(secret);
        secretDataRepository.save(secretData);

        return secretMapper.secretToCreateSecretResponse(savedSecret);
    }

    /**
     * Reads the metadata of a specific secret.
     *
     * @param projectId  The ID of the project.
     * @param secretName The name of the secret.
     * @return {@link SecretMetadata} for the requested secret.
     * @throws SecretNotFoundException if the secret is not found.
     */
    @Override
    public SecretMetadata readSecretMetadata(String projectId, String secretName) {
        Secret secret = findSecretOrThrow(projectId, secretName);
        return secretMapper.secretToSecretMetadata(secret);
    }

    /**
     * Reads the value of a specific secret.
     *
     * @param projectId  The ID of the project.
     * @param secretName The name of the secret.
     * @return A {@link SecretDataResponse} containing the secret's value.
     * @throws SecretNotFoundException if the secret or its data is not found.
     */
    @Override
    public SecretDataResponse readSecretValue(String projectId, String secretName) {
        Secret secret = findSecretOrThrow(projectId, secretName);

        SecretData secretData = secretDataRepository.findBySecretIdAndDataVersion(secret.getId(), secret.getCurrentDataVersion())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Secret data not found for secret: " + secret.getId()));
        secretEncryptionUtil.decryptSecretData(secretData);

        return secretMapper.toSecretDataResponse(secret, secretData);
    }

    /**
     * Upgrades the data of an existing secret, creating a new version.
     *
     * @param projectId  The ID of the project.
     * @param secretName The name of the secret to upgrade.
     * @param request    The request containing the new secret data.
     * @return An {@link UpgradeSecretDataResponse} with the new data version.
     * @throws SecretNotFoundException if the secret is not found.
     */
    @Override
    @Transactional
    @Auditable(action = AuditAction.UPGRADE_SECRET_DATA)
    public UpgradeSecretDataResponse upgradeSecretData(String projectId, String secretName, UpgradeSecretDataRequest request) {
        Secret secret = findSecretOrThrow(projectId, secretName);

        String keyId = getKeyIdForProject(projectId);
        int newVersion = secret.getCurrentDataVersion() + 1;

        SecretData secretData = secretMapper.upgradeRequestToSecretData(request, secret, newVersion);
        secretEncryptionUtil.encryptSecretData(secretData, keyId);
        secretDataRepository.save(secretData);

        secret.setCurrentDataVersion(newVersion);
        secret.setUpdatedBy(SYSTEM_USER);
        secretRepository.save(secret);

        UpgradeSecretDataResponse response = new UpgradeSecretDataResponse();
        response.setDataVersion(newVersion);
        return response;
    }

    /**
     * Deletes a secret from a project.
     *
     * @param projectId  The ID of the project.
     * @param secretName The name of the secret to delete.
     */
    @Override
    @Transactional
    @Auditable(action = AuditAction.DELETE_SECRET)
    public void deleteSecret(String projectId, String secretName) {
        Secret secret = findSecretOrThrow(projectId, secretName);
        secretRepository.delete(secret);
    }

    /**
     * Retrieves a specific version of a secret's data.
     *
     * @param projectId  The ID of the project.
     * @param secretName The name of the secret.
     * @param version    The version of the secret data to retrieve.
     * @return A {@link SecretDataVersionResponse} containing the secret data for the specified version.
     * @throws SecretNotFoundException if the secret or the specific version of the data is not found.
     */
    @Override
    public SecretDataVersionResponse getSecretDataVersion(String projectId, String secretName, int version) {
        Secret secret = findSecretOrThrow(projectId, secretName);

        SecretData secretData = secretDataRepository.findBySecretIdAndDataVersion(secret.getId(), version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Secret with name " + secretName + " and version " + version + " not found."));
        secretEncryptionUtil.decryptSecretData(secretData);

        return secretMapper.secretDataToSecretDataVersionResponse(secret, secretData);
    }

    private Secret findSecretOrThrow(String projectId, String secretName) {
        return secretRepository.findByProjectIdAndName(projectId, secretName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Secret not found with name: " + secretName));
    }

    private String getKeyIdForProject(String projectId) {
        // TODO: Implement a proper key selection strategy based on the project
        log.warn("Using default key for project {}. A proper key selection strategy should be implemented.", projectId);
        return cryptoConfig.getKeys().keySet().stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No crypto keys configured."));
    }
} 