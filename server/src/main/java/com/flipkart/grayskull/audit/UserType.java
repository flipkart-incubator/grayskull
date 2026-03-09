package com.flipkart.grayskull.audit;

/**
 * Enum representing user types for audit entry queries.
 * Used to filter audit entries based on the type of user that performed the action.
 * 
 * To retrieve all audit entries without filtering by user type, omit the userType parameter
 * in API calls.
 */
public enum UserType {
    /**
     * Filter for service/system users
     */
    SERVICE,
    
    /**
     * Filter for human users
     */
    HUMAN
}
