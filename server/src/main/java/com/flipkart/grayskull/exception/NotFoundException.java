package com.flipkart.grayskull.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class NotFoundException extends ResponseStatusException {
    public NotFoundException(String reason) {
        super(HttpStatus.NOT_FOUND, reason);
    }
}
