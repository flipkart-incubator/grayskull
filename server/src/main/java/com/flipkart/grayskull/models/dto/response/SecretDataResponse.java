package com.flipkart.grayskull.models.dto.response;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

/**
 * Secret data values with version metadata.
 * Contains sensitive information for authorized access.
 */
@Getter
@ToString
@EqualsAndHashCode
@AllArgsConstructor
@SuperBuilder
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SecretDataResponse {

    /**
     * Data version number.
     */
    int dataVersion;

    /**
     * Public part of the secret.
     */
    String publicPart;

    /**
     * Private/sensitive part of the secret.
     */
    String privatePart;

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

    /**
     * Version state (ACTIVE, EXPIRED, REVOKED).
     */
    String state;
}
