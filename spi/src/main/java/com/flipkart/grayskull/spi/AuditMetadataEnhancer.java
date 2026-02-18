package com.flipkart.grayskull.spi;

import java.util.Map;

public interface AuditMetadataEnhancer {
    Map<String, String> getAdditionalMetadata();
}
