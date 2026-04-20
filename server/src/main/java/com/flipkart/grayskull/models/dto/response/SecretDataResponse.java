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
 * <p>
 * Uses {@code @SuperBuilder} (non-final class with private-final fields) so that
 * DTOs such as {@link BatchSecretItem} can extend it and reuse its fields via
 * inheritance instead of duplicating them.
 */
@Getter
@ToString
@EqualsAndHashCode
@AllArgsConstructor
@SuperBuilder
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SecretDataResponse {

    int dataVersion;
    String publicPart;
    String privatePart;
    Instant lastRotated;
    Instant creationTime;
    Instant updatedTime;
    String createdBy;
    String updatedBy;
    String state;
}
