package com.flipkart.grayskull.models.dto.request;

import lombok.*;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to create a new secret provider.
 */
@Getter
@Setter
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
public class CreateSecretProviderRequest extends SecretProviderRequest {
    
    /**
     * Provider name, must be unique (max 255 chars).
     */
    @NotBlank
    @Size(max = 50)
    private String name;

}
