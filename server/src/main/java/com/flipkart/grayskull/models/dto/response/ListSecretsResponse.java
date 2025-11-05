package com.flipkart.grayskull.models.dto.response;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

/**
 * Paginated list of secrets within a project.
 */
@Value
@Builder
@AllArgsConstructor
public class ListSecretsResponse {

    /**
     * Secret metadata for current page.
     */
    List<SecretMetadata> secrets;

    /**
     * Total number of secrets matching criteria.
     */
    long total;
}
