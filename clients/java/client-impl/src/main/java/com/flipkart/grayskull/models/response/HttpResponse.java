package com.flipkart.grayskull.models.response;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Represents a raw HTTP response.
 * <p>
 * This class captures the essential parts of an HTTP response needed for processing:
 * status code, body, content type, and protocol. It is immutable and thread-safe.
 * </p>
 */
@Getter
@RequiredArgsConstructor
public class HttpResponse {
    private final int statusCode;
    private final String body;
    private final String contentType;
    private final String protocol;
}

