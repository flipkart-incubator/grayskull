package com.flipkart.grayskull.models.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single entry in a batch-get request, identifying a secret and its last known version.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SecretVersionEntry {

    @NotBlank
    @Size(max = 255)
    private String projectId;

    @NotBlank
    @Size(max = 255)
    private String secretName;

    /**
     * The last version number the caller has cached for this secret.
     */
    @PositiveOrZero
    private Integer lastKnownVersion;
}
