package com.flipkart.grayskull.service.implementations;

import com.flipkart.grayskull.audit.Auditable;
import com.flipkart.grayskull.configuration.CryptoConfig;
import com.flipkart.grayskull.mappers.SecretMapper;
import com.flipkart.grayskull.models.db.Secret;
import com.flipkart.grayskull.models.db.SecretData;
import com.flipkart.grayskull.models.dto.request.CreateSecretRequest;
import com.flipkart.grayskull.models.dto.request.UpgradeSecretDataRequest;
import com.flipkart.grayskull.models.dto.response.CreateSecretResponse;
import com.flipkart.grayskull.models.dto.response.ListSecretsResponse;
import com.flipkart.grayskull.models.dto.response.SecretDataResponse;
import com.flipkart.grayskull.models.dto.response.SecretDataVersionResponse;
import com.flipkart.grayskull.models.dto.response.SecretMetadata;
import com.flipkart.grayskull.models.dto.response.UpgradeSecretDataResponse;
import com.flipkart.grayskull.models.enums.AuditAction;
import com.flipkart.grayskull.models.enums.SecretState;
import com.flipkart.grayskull.repositories.SecretDataRepository;
import com.flipkart.grayskull.repositories.SecretRepository;
import com.flipkart.grayskull.service.interfaces.SecretService;
import com.flipkart.grayskull.service.utils.SecretEncryptionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

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
        List<Secret> secrets = secretRepository.findByProjectIdAndState(projectId, SecretState.ACTIVE, pageable);
        long total = secretRepository.countByProjectIdAndState(projectId, SecretState.ACTIVE);
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
        Secret savedSecret = secretRepository.save(secret);

        SecretData secretData = secretMapper.requestToSecretData(request, savedSecret.getId());
        secretEncryptionUtil.encryptSecretData(secretData, keyId);
        secretDataRepository.save(secretData);
        savedSecret.setData(secretData);


        return secretMapper.secretToCreateSecretResponse(savedSecret);
    }

    /**
     * Reads the metadata of a specific secret.
     *
     * @param projectId  The ID of the project.
     * @param secretName The name of the secret.
     * @return {@link SecretMetadata} for the requested secret.
     */
    @Override
    public SecretMetadata readSecretMetadata(String projectId, String secretName) {
        Secret secret = findActiveSecretOrThrow(projectId, secretName);
        return secretMapper.secretToSecretMetadata(secret);
    }

    /**
     * Reads the value of a specific secret.
     *
     * @param projectId  The ID of the project.
     * @param secretName The name of the secret.
     * @return A {@link SecretDataResponse} containing the secret's value.
     */
    @Override
    public SecretDataResponse readSecretValue(String projectId, String secretName) {
        Secret secret = findActiveSecretOrThrow(projectId, secretName);

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
     */
    @Override
    @Transactional
    @Auditable(action = AuditAction.UPGRADE_SECRET_DATA)
    public UpgradeSecretDataResponse upgradeSecretData(String projectId, String secretName, UpgradeSecretDataRequest request) {
        Secret secret = findActiveSecretOrThrow(projectId, secretName);

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
     * Disables a secret, marking it as soft-deleted.
     *
     * @param projectId  The ID of the project.
     * @param secretName The name of the secret to disable.
     */
    @Override
    @Transactional
    @Auditable(action = AuditAction.DELETE_SECRET)
    public void deleteSecret(String projectId, String secretName) {
        Secret secret = findActiveSecretOrThrow(projectId, secretName);
        secret.setState(SecretState.DISABLED);
        secretRepository.save(secret);
    }

    /**
     * Retrieves a specific version of a secret's data.
     *
     * @param projectId  The ID of the project.
     * @param secretName The name of the secret.
     * @param version    The version of the secret data to retrieve.
     * @param state      Optional state of the secret.
     * @return A {@link SecretDataVersionResponse} containing the secret data for the specified version.
     */
    @Override
    public SecretDataVersionResponse getSecretDataVersion(String projectId, String secretName, int version, Optional<SecretState> state) {
        Secret secret = state.map(secretState -> secretRepository.findByProjectIdAndNameAndState(projectId, secretName, secretState)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Secret with name " + secretName + " and state " + secretState + " not found.")))
                .orElseGet(() -> findActiveSecretOrThrow(projectId, secretName));

        SecretData secretData = secretDataRepository.findBySecretIdAndDataVersion(secret.getId(), version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Secret with name " + secretName + " and version " + version + " not found."));
        secretEncryptionUtil.decryptSecretData(secretData);

        return secretMapper.secretDataToSecretDataVersionResponse(secret, secretData);
    }

    private Secret findSecretOrThrow(String projectId, String secretName) {
        return secretRepository.findByProjectIdAndName(projectId, secretName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Secret not found with name: " + secretName));
    }

    private Secret findActiveSecretOrThrow(String projectId, String secretName) {
        return secretRepository.findByProjectIdAndNameAndState(projectId, secretName, SecretState.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Active secret not found with name: " + secretName));
    }

    private String getKeyIdForProject(String projectId) {
        // TODO: Implement a proper key selection strategy based on the project
        log.warn("Using default key for project {}. A proper key selection strategy should be implemented.", projectId);
        return cryptoConfig.getKeys().keySet().stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No crypto keys configured."));
    }
} 