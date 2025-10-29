package com.flipkart.grayskull.models.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

/**
 * Response after upgrading secret data (creating new version).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpgradeSecretDataResponse {

    /**
     * Project identifier.
     */
    String projectId;

    /**
     * Secret name.
     */
    String name;

    /**
     * New data version number.
     */
    int dataVersion;

    /**
     * Last rotation timestamp.
     */
    Instant lastRotated;

    /**
     * Version creation timestamp.
     */
    Instant creationTime;

    /**
     * Last update timestamp.
     */
    Instant updatedTime;

    /**
     * User who created this version.
     */
    String createdBy;

    /**
     * User who last updated this version.
     */
    String updatedBy;
}
