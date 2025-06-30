package com.flipkart.grayskull.models.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
@AllArgsConstructor
public class ListSecretsResponse {
    List<SecretMetadata> secrets;
    long total;
} 