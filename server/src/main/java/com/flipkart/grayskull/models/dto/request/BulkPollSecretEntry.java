package com.flipkart.grayskull.models.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single entry in a bulk-poll request, identifying a secret and its last known version.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BulkPollSecretEntry {

    @NotBlank
    @Size(max = 255)
    private String projectId;

    @NotBlank
    @Size(max = 255)
    private String secretName;

    @Min(0)
    private int lastKnownVersion;
}
