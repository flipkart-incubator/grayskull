package com.flipkart.grayskull.audit;

import lombok.experimental.UtilityClass;

/**
 * Constants used in the auditing system.
 */
@UtilityClass
public class AuditConstants {
    
    /**
     * Parameter name for project ID in method arguments.
     */
    public static final String PROJECT_ID_PARAM = "projectId";
    
    /**
     * Parameter name for secret name in method arguments.
     */
    public static final String SECRET_NAME_PARAM = "secretName";
    
    /**
     * Default value when a parameter is not found or is null.
     */
    public static final String UNKNOWN_VALUE = "UNKNOWN";
    
    /**
     * Default user identifier for system operations.
     */
    public static final String DEFAULT_USER = "system";
    
    /**
     * Metadata key for request parameters.
     */
    public static final String REQUEST_METADATA_KEY = "request";
    
    /**
     * Metadata key for method result.
     */
    public static final String RESULT_METADATA_KEY = "result";
    
    /**
     * Resource type for secret entities.
     */
    public static final String RESOURCE_TYPE_SECRET = "SECRET";
    
}
