package com.flipkart.grayskull.models.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Represents a single validation violation in an error response.
 * Contains the field path and the associated error message.
 */
@Getter
@AllArgsConstructor
public class Violation {
    /**
     * The field path that failed validation (e.g., "email", "user.age").
     * For non-field-specific errors, this may be empty or represent a general path.
     */
    private final String field;
    
    /**
     * The validation error message describing what went wrong.
     */
    private final String message;
}

