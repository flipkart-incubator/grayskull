package com.flipkart.grayskull.models.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Response for the batch-get secrets endpoint.
 */
@Value
@Builder
@AllArgsConstructor
public class BatchGetSecretsResponse {

    int updatedCount;
    List<BatchSecretItem> updatedSecrets;
}
