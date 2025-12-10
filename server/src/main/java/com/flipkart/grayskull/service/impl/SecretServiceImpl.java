package com.flipkart.grayskull.service.impl;

import com.flipkart.grayskull.audit.Audit;
import com.flipkart.grayskull.audit.AuditAction;
import com.flipkart.grayskull.configuration.KmsConfig;
import com.flipkart.grayskull.entities.ProjectEntity;
import com.flipkart.grayskull.mappers.SecretMapper;
import com.flipkart.grayskull.spi.models.Project;
import com.flipkart.grayskull.spi.models.Secret;
import com.flipkart.grayskull.spi.models.SecretData;
import com.flipkart.grayskull.models.dto.request.CreateSecretRequest;
import com.flipkart.grayskull.models.dto.request.UpgradeSecretDataRequest;
import com.flipkart.grayskull.models.dto.response.SecretResponse;
import com.flipkart.grayskull.models.dto.response.ListSecretsResponse;
import com.flipkart.grayskull.models.dto.response.SecretDataResponse;
import com.flipkart.grayskull.models.dto.response.SecretDataVersionResponse;
import com.flipkart.grayskull.models.dto.response.SecretMetadata;
import com.flipkart.grayskull.models.dto.response.UpgradeSecretDataResponse;
import com.flipkart.grayskull.spi.models.enums.LifecycleState;
import com.flipkart.grayskull.spi.repositories.ProjectRepository;
import com.flipkart.grayskull.spi.repositories.SecretDataRepository;
import com.flipkart.grayskull.spi.repositories.SecretRepository;
import com.flipkart.grayskull.service.interfaces.SecretService;
import com.flipkart.grayskull.service.utils.AuthnUtil;
import com.flipkart.grayskull.service.utils.SecretEncryptionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SecretServiceImpl implements SecretService {

    private final SecretRepository secretRepository;
    private final SecretDataRepository secretDataRepository;
    private final SecretMapper secretMapper;
    private final SecretEncryptionUtil secretEncryptionUtil;
    private final KmsConfig kmsConfig;
    private final ProjectRepository projectRepository;
    private final AuthnUtil authnUtil;

    /**
     * Lists secrets for a given project with pagination.
     *
     * @param projectId The ID of the project.
     * @param offset    The starting offset for pagination.
     * @param limit     The maximum number of secrets to return.
     * @return A {@link ListSecretsResponse} containing the list of secret metadata
     *         and the total count.
     */
    @Override
    public ListSecretsResponse listSecrets(String projectId, int offset, int limit) {
        List<Secret> secrets = secretRepository.findByProjectIdAndState(projectId, LifecycleState.ACTIVE, offset,
                limit);
        long total = secretRepository.countByProjectIdAndState(projectId, LifecycleState.ACTIVE);
        List<SecretMetadata> secretMetadata = secrets.stream()
                .map(secretMapper::secretToSecretMetadata)
                .toList();
        return new ListSecretsResponse(secretMetadata, total);
    }

    /**
     * Creates a new secret for a given project.
     *
     * @param projectId The ID of the project.
     * @param request   The request body containing the secret details.
     * @return A {@link SecretResponse} containing the details of the created
     *         secret.
     */
    @Override
    @Transactional
    @Audit(action = AuditAction.CREATE_SECRET)
    public SecretResponse createSecret(String projectId, CreateSecretRequest request) {
        // TODO: Add explicit project existence check if auto-create semantic changes.
        // Currently, resolveKmsKeyId auto-creates projects via getOrCreateProject.
        // If this behavior is removed in the future, add validation here:
        // projectRepository.findById(projectId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        
        secretRepository.findByProjectIdAndName(projectId, request.getName())
                .ifPresent(s -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "A secret with the same name " + request.getName() + " already exists.");
                });

        String keyId = resolveKmsKeyId(projectId);

        Secret secret = secretMapper.requestToSecret(request, projectId, authnUtil.getCurrentUsername());
        Secret savedSecret = secretRepository.save(secret);

        SecretData secretData = secretMapper.requestToSecretData(request, savedSecret.getId());
        secretEncryptionUtil.encryptSecretData(secretData, keyId);
        secretDataRepository.save(secretData);
        savedSecret.setData(secretData);

        return secretMapper.secretToSecretResponse(savedSecret);
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

        SecretData secretData = secretDataRepository
                .getBySecretIdAndDataVersion(secret.getId(), secret.getCurrentDataVersion())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Secret data not found for secret: " + secret.getId()));
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
    @Audit(action = AuditAction.UPGRADE_SECRET_DATA)
    public UpgradeSecretDataResponse upgradeSecretData(String projectId, String secretName,
            UpgradeSecretDataRequest request) {
        // TODO: Add explicit project existence check if auto-create semantic changes.
        // Currently, resolveKmsKeyId auto-creates projects via getOrCreateProject.
        // If this behavior is removed in the future, add validation here:
        // projectRepository.findById(projectId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        
        Secret secret = findActiveSecretOrThrow(projectId, secretName);

        if (!"SELF".equals(secret.getProvider())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upgrade secret is supported only for self-managed secrets");
        }

        String keyId = resolveKmsKeyId(projectId);
        int newVersion = secret.getCurrentDataVersion() + 1;

        // Update Secret FIRST to leverage optimistic locking
        // If concurrent modification occurs, this will fail early before creating
        // orphaned SecretData
        secret.setCurrentDataVersion(newVersion);
        secret.setUpdatedBy(authnUtil.getCurrentUsername());
        secretRepository.save(secret); // May throw OptimisticLockingFailureException

        // Only create and save SecretData after Secret update succeeds
        SecretData secretData = secretMapper.upgradeRequestToSecretData(request, secret, newVersion);
        secretEncryptionUtil.encryptSecretData(secretData, keyId);
        secretDataRepository.save(secretData);

        UpgradeSecretDataResponse response = new UpgradeSecretDataResponse();
        response.setProjectId(projectId);
        response.setName(secretName);
        response.setDataVersion(newVersion);
        response.setLastRotated(secret.getLastRotated());
        response.setCreationTime(secret.getCreationTime());
        response.setUpdatedTime(secret.getUpdatedTime());
        response.setCreatedBy(secret.getCreatedBy());
        response.setUpdatedBy(secret.getUpdatedBy());
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
    @Audit(action = AuditAction.DELETE_SECRET)
    public void deleteSecret(String projectId, String secretName) {
        Secret secret = findActiveSecretOrThrow(projectId, secretName);
        secret.setState(LifecycleState.DISABLED);
        secret.setUpdatedBy(authnUtil.getCurrentUsername());
        secretRepository.save(secret);
    }

    /**
     * Retrieves a specific version of a secret's data.
     *
     * @param projectId  The ID of the project.
     * @param secretName The name of the secret.
     * @param version    The version of the secret data to retrieve.
     * @param state      Optional state of the secret.
     * @return A {@link SecretDataVersionResponse} containing the secret data for
     *         the specified version.
     */
    @Override
    public SecretDataVersionResponse getSecretDataVersion(String projectId, String secretName, int version,
            Optional<LifecycleState> state) {
        Secret secret = state
                .map(secretState -> secretRepository.findByProjectIdAndNameAndState(projectId, secretName, secretState)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "Secret with name " + secretName + " and state " + secretState + " not found.")))
                .orElseGet(() -> findActiveSecretOrThrow(projectId, secretName));

        SecretData secretData = secretDataRepository.getBySecretIdAndDataVersion(secret.getId(), version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Secret with name " + secretName + " and version " + version + " not found."));
        secretEncryptionUtil.decryptSecretData(secretData);

        return secretMapper.secretDataToSecretDataVersionResponse(secret, secretData);
    }

    /**
     * Finds an active secret for a given project and secret name or throws a 404
     * Not Found exception.
     *
     * @param projectId  The ID of the project.
     * @param secretName The name of the secret.
     * @return The {@link Secret} if found.
     * @throws ResponseStatusException if no active secret is found.
     */
    private Secret findActiveSecretOrThrow(String projectId, String secretName) {
        return secretRepository.findByProjectIdAndNameAndState(projectId, secretName, LifecycleState.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Active secret not found with name: " + secretName));
    }

    /**
     * Retrieves a project by its ID. If the project does not exist, it creates a
     * new one with the default KMS key, saves it, and returns the new instance.
     * <p>
     * TODO: This auto-creation behavior may change in the future. If so, this method
     * should be updated to throw an exception when the project doesn't exist, and
     * calling APIs (createSecret, upgradeSecretData) should add explicit project
     * existence checks before proceeding with their operations.
     *
     * @param projectId The ID of the project to get or create.
     * @return The existing or newly created {@link Project}.
     */
    @Transactional
    public Project getOrCreateProject(String projectId) {
        return projectRepository.findById(projectId).orElseGet(() -> {
            String defaultKeyId = kmsConfig.getDefaultKeyId();
            ProjectEntity newProject = ProjectEntity.builder()
                    .id(projectId)
                    .kmsKeyId(defaultKeyId)
                    .build();
            return projectRepository.save(newProject);
        });
    }

    /**
     * Resolves the KMS key ID to be used for encryption for a given project.
     * It first checks for a project-specific key. If one is not defined, it falls
     * back
     * to the default KMS key.
     *
     * @param projectId The ID of the project.
     * @return The resolved KMS key ID as a String.
     */
    private String resolveKmsKeyId(String projectId) {
        Project project = getOrCreateProject(projectId);
        String keyId = project.getKmsKeyId();
        if (keyId == null || keyId.isEmpty()) {
            return kmsConfig.getDefaultKeyId();
        }
        return keyId;
    }
}
