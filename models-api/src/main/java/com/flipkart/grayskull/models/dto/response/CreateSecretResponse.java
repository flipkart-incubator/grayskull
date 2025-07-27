package com.flipkart.grayskull.models.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import java.time.Instant;
import java.util.Map;

@Data
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateSecretResponse {
    String projectId;
    String name;
    Map<String, String> systemLabels;
    int currentDataVersion;
    Instant lastRotated;
    String state;
    String provider;
    Map<String, Object> providerMeta;
    int metadataVersion;
} 