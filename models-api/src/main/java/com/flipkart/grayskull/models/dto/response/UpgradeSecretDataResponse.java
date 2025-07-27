package com.flipkart.grayskull.models.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpgradeSecretDataResponse {
    int dataVersion;
    Instant lastRotated;
    Instant creationTime;
    Instant updatedTime;
    String createdBy;
    String updatedBy;
} 