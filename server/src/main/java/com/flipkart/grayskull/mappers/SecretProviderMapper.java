package com.flipkart.grayskull.mappers;

import com.flipkart.grayskull.entities.SecretProviderEntity;
import com.flipkart.grayskull.models.dto.request.CreateSecretProviderRequest;
import com.flipkart.grayskull.models.dto.request.SecretProviderRequest;
import com.flipkart.grayskull.spi.models.SecretProvider;
import org.mapstruct.*;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
@AnnotateWith(GeneratedMapper.class)
public interface SecretProviderMapper {

    /**
     * Maps a {@link SecretProvider} to a {@link SecretProviderEntity}. This always creates a new object even if it is already an instance of {@link SecretProviderEntity}.
     *
     * @param secretProvider the {@link SecretProvider} to map
     * @return new SecretProviderEntity object
     */
    SecretProviderEntity mapToEntity(SecretProvider secretProvider);

    /**
     * Maps a {@link SecretProvider} to a {@link SecretProviderEntity}. This checks if the object is already an instance of {@link SecretProviderEntity} and returns it if it is.
     * Otherwise, it creates a new object.
     *
     * @param secretProvider the {@link SecretProvider} to map
     * @return new SecretProviderEntity object
     */
    default SecretProviderEntity toEntity(SecretProvider secretProvider) {
        if (secretProvider instanceof SecretProviderEntity entity) {
            return entity;
        }
        return mapToEntity(secretProvider);
    }

    @Mapping(source = "authAttributesProcessed", target = "authAttributes")
    SecretProvider requestToSecretProvider(CreateSecretProviderRequest request);

    @Mapping(source = "authAttributesProcessed", target = "secretProvider.authAttributes")
    void updateSecretProviderFromRequest(SecretProviderRequest request, @MappingTarget SecretProvider secretProvider);
}
