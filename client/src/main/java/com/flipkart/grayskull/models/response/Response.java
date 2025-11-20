package com.flipkart.grayskull.models.response;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Generic class for deserializing the server's ResponseTemplate.
 * This represents the standard response format from the Grayskull API.
 * <p>
 * This class is immutable and thread-safe.
 * </p>
 *
 * @param <T> the type of the data field
 */
@Getter
@AllArgsConstructor(onConstructor = @__(@JsonCreator))
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Response<T> {
    
    /**
     * The actual response data.
     */
    private final T data;
    
    /**
     * A human-readable message describing the response.
     */
    private final String message;
}
