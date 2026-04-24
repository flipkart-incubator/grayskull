package com.flipkart.grayskull.models.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request body for the batch-get secrets endpoint.
 * Contains a list of secrets to check for version changes.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchGetSecretsRequest {

    @NotNull
    @Size(min = 1, max = 50, message = "Must contain between 1 and 50 entries")
    @Valid
    private List<SecretVersionEntry> secrets;
}
