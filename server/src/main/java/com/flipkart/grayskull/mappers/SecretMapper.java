package com.flipkart.grayskull.mappers;

import com.flipkart.grayskull.entities.SecretDataEntity;
import com.flipkart.grayskull.entities.SecretEntity;
import com.flipkart.grayskull.spi.models.Secret;
import com.flipkart.grayskull.spi.models.SecretData;
import com.flipkart.grayskull.models.dto.request.CreateSecretRequest;
import com.flipkart.grayskull.models.dto.request.UpgradeSecretDataRequest;
import com.flipkart.grayskull.models.dto.response.SecretResponse;
import com.flipkart.grayskull.models.dto.response.SecretDataResponse;
import com.flipkart.grayskull.models.dto.response.SecretDataVersionResponse;
import com.flipkart.grayskull.models.dto.response.SecretMetadata;
import com.flipkart.grayskull.spi.models.enums.LifecycleState;
import org.mapstruct.InheritConfiguration;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.UUID;

/**
 * Maps between Secret domain models (entities) and Data Transfer Objects
 * (DTOs).
 * <p>
 * This mapper uses MapStruct to generate the implementation at compile-time,
 * ensuring high performance and type safety.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE, imports = { UUID.class,
        LifecycleState.class })
public interface SecretMapper {

    /**
     * Maps a {@link CreateSecretRequest} to a {@link SecretEntity}.
     * Sets default values for metadata. Timestamps are managed by Spring Data auditing.
     */
    default SecretEntity requestToSecret(CreateSecretRequest request, String projectId, String systemUser) {
        return SecretEntity.builder()
                .id(UUID.randomUUID().toString())
                .projectId(projectId)
                .name(request.getName())
                .systemLabels(null)
                .currentDataVersion(1)
                .lastRotated(null)
                .state(LifecycleState.ACTIVE)
                .provider(request.getProvider() != null ? request.getProvider().name() : null)
                .providerMeta(request.getProviderMeta())
                .metadataVersion(1)
                .version(null)
                .createdBy(systemUser)
                .updatedBy(systemUser)
                .data(null)
                .build();
    }

    /**
     * Maps a {@link CreateSecretRequest} to a {@link SecretDataEntity}.
     */
    @Mapping(target = "id", expression = "java(UUID.randomUUID().toString())")
    @Mapping(target = "privatePart", source = "request.data.privatePart")
    @Mapping(target = "publicPart", source = "request.data.publicPart")
    @Mapping(target = "dataVersion", constant = "1L")
    @Mapping(target = "secretId", source = "secretId")
    SecretDataEntity requestToSecretData(CreateSecretRequest request, String secretId);

    /**
     * Creates a new {@link SecretDataEntity} for an upgrade request.
     */
    @Mapping(target = "id", expression = "java(UUID.randomUUID().toString())")
    @Mapping(target = "secretId", source = "secret.id")
    @Mapping(target = "dataVersion", source = "newVersion")
    SecretDataEntity upgradeRequestToSecretData(UpgradeSecretDataRequest request, Secret secret, int newVersion);

    /**
     * Maps a {@link Secret} entity to a {@link SecretMetadata} DTO.
     * This is the generic mapping from source entity to destination entity,
     * reusable across create/get/list operations.
     */
    SecretMetadata secretToSecretMetadata(Secret secret);

    /**
     * Maps a {@link Secret} entity to a {@link SecretResponse} DTO.
     * This is the generic mapping from source entity to destination entity,
     * reusable across create/get operations.
     */
    SecretResponse secretToSecretResponse(Secret secret);

    /**
     * Maps a {@link Secret} entity and its corresponding {@link SecretData}
     * to a comprehensive {@link SecretDataResponse} DTO.
     *
     * @param secret     The main secret entity, providing metadata.
     * @param secretData The secret data entity, providing the value and version
     *                   info.
     * @return A {@link SecretDataResponse} DTO.
     */
    @Mapping(target = "dataVersion", source = "secretData.dataVersion")
    @Mapping(target = "publicPart", source = "secretData.publicPart")
    @Mapping(target = "privatePart", source = "secretData.privatePart")
    @Mapping(target = "state", source = "secret.state")
    @Mapping(target = "lastRotated", source = "secret.lastRotated")
    @Mapping(target = "creationTime", source = "secret.creationTime")
    @Mapping(target = "updatedTime", source = "secret.updatedTime")
    @Mapping(target = "createdBy", source = "secret.createdBy")
    @Mapping(target = "updatedBy", source = "secret.updatedBy")
    SecretDataResponse toSecretDataResponse(Secret secret, SecretData secretData);

    /**
     * Maps a {@link SecretData} entity to a {@link SecretDataVersionResponse} DTO.
     */
    @InheritConfiguration(name = "toSecretDataResponse")
    @Mapping(target = "state", source = "secret.state")
    SecretDataVersionResponse secretDataToSecretDataVersionResponse(Secret secret, SecretData secretData);

    /**
     * Converts a {@link LifecycleState} enum to its string representation.
     * This method is used by MapStruct automatically for any LifecycleState ->
     * String mapping.
     *
     * @param state The enum to convert.
     * @return The uppercase name of the enum (e.g., "ACTIVE").
     */
    default String lifecycleStateToString(LifecycleState state) {
        return state == null ? null : state.name();
    }
}