package com.flipkart.grayskull.models.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single entry in a batch-get request, identifying a secret and its last known version.
 * <p>
 * {@code lastKnownVersion} is optional. When {@code null}, the caller has no prior cached
 * version and the server will always return the current value for this secret. When
 * provided, the server returns the secret only if its current version is strictly greater
 * than {@code lastKnownVersion}.
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
     * The last version number the caller has cached for this secret. May be {@code null}
     * when the caller has no prior record and wants the latest value unconditionally.
     * {@code @PositiveOrZero} is skipped when the value is null, so both semantics are preserved.
     */
    @PositiveOrZero
    private Integer lastKnownVersion;
}
