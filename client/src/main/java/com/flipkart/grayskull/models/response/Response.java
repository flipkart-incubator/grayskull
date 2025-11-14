package com.flipkart.grayskull.models.response;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    /**
     * Constructor for JSON deserialization.
     *
     * @param data    The actual response data
     * @param message A human-readable message describing the response
     */
    @JsonCreator
    public Response(
            @JsonProperty("data") T data,
            @JsonProperty("message") String message) {
        this.data = data;
        this.message = message;
    }
}

