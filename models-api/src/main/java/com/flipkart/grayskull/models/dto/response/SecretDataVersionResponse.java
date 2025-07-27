package com.flipkart.grayskull.models.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import java.time.Instant;

@Value
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SecretDataVersionResponse {
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