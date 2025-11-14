package com.flipkart.grayskull.models.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Internal wrapper to hold both HTTP status code and the deserialized Response object.
 */
@Getter
@AllArgsConstructor
public final class ResponseWithStatus<T> {
    final int statusCode;
    final Response<T> response;
}
