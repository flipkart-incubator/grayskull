package com.flipkart.grayskull.models.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A standardized wrapper for all API responses.
 *
 * @param <T> The type of the data being returned in the response.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ResponseTemplate<T> {
    private T data;
    private String message;
    private String code;
    
    /**
     * A list of field-level validation violations.
     * This field is only present for validation errors and contains structured
     * information about which fields failed validation and why.
     */
    private List<Violation> violations;

    /**
     * Private constructor to enforce the use of static factory methods.
     *
     * @param data       The response payload.
     * @param message    A descriptive message.
     * @param code       An optional error code.
     * @param violations A list of field-level validation violations.
     */
    private ResponseTemplate(T data, String message, String code, List<Violation> violations) {
        this.data = data;
        this.message = message;
        this.code = code;
        this.violations = violations;
    }

    /**
     * Creates a success response with data and a message.
     *
     * @param data    The payload to be returned.
     * @param message A descriptive message.
     * @param <T>     The type of the payload.
     * @return A ResponseTemplate instance for a successful operation with data.
     */
    public static <T> ResponseTemplate<T> success(T data, String message) {
        return new ResponseTemplate<>(data, message, null, null);
    }

    /**
     * Creates a success response with only a message, for operations that don't return data.
     *
     * @param message A descriptive message.
     * @return A ResponseTemplate instance for a successful operation without data.
     */
    public static ResponseTemplate<Void> success(String message) {
        return new ResponseTemplate<>(null, message, null, null);
    }

    /**
     * Creates an error response with a message and an error code.
     *
     * @param message A descriptive error message.
     * @param code    A unique code identifying the error.
     * @return A ResponseTemplate instance for a failed operation.
     */
    public static ResponseTemplate<Void> error(String message, String code) {
        return new ResponseTemplate<>(null, message, code, null);
    }
    
    /**
     * Creates an error response with structured field violations.
     * Used for validation errors where specific fields failed validation.
     *
     * @param message    A general error message.
     * @param code       A unique code identifying the error type.
     * @param violations A list of field-level validation violations.
     * @return A ResponseTemplate instance for a validation failure.
     */
    public static ResponseTemplate<Void> validationError(String message, String code, List<Violation> violations) {
        return new ResponseTemplate<>(null, message, code, violations);
    }
} 