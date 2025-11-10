package com.flipkart.grayskull.models.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Generic class for deserializing the server's ResponseTemplate.
 * This represents the standard response format from the Grayskull API.
 *
 * @param <T> the type of the data field
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Response<T> {
    
    /**
     * The actual response data.
     */
    private T data;
    
    /**
     * A human-readable message describing the response.
     */
    private String message;
    
    /**
     * A response code for categorizing the response.
     */
    private String code;
}

