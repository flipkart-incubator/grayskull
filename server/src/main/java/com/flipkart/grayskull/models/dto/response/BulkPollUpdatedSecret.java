package com.flipkart.grayskull.models.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

/**
 * Represents a secret whose value has changed since the client's last known version.
 * Contains the full decrypted secret data for the current version.
 */
@Value
@Builder
@AllArgsConstructor
public class BulkPollUpdatedSecret {

    String projectId;
    String secretName;
    SecretDataResponse secretValue;
}
