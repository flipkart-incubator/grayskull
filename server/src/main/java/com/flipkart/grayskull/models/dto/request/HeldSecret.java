package com.flipkart.grayskull.models.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A secret held by the calling workload, reported in a heartbeat.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HeldSecret {

    @NotBlank
    @Size(max = 255)
    private String projectId;

    @NotBlank
    @Size(max = 255)
    private String secretName;

    /**
     * The newest version of the secret the reporting instance holds.
     */
    @NotNull
    @Min(1)
    private Integer version;

    /**
     * Whether the reporting instance has a refresh hook registered for this secret. Defaults to false.
     */
    private boolean refreshHook;
}
