package com.flipkart.grayskull.spi;

import java.util.Map;

/**
 * An interface for adding more details to the audit metadata.
 * <p>
 * Implementations of this interface should provide a method that returns a map of additional metadata that needs to be
 * added to the audit log. These additional metadata properties are not predefined and are specific to the implementation.
 * <p>
 * The additional metadata is merged with the existing audit metadata and is available in the audit log.
 * <p>
 * The order of invocation of implementations is not guaranteed.
 */
public interface AuditMetadataEnhancer {
    /**
     * Returns a map of additional metadata that needs to be added to the audit log. Returned value may be null in which case it is ignored
     *
     * @return A map of additional metadata.
     */
    Map<String, String> getAdditionalMetadata();
}
