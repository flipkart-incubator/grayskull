package com.flipkart.grayskull.models.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor(onConstructor = @__(@JsonCreator))
public final class SecretVersionEntry {
    private final String projectId;
    private final String secretName;
    private final int lastKnownVersion;
}
