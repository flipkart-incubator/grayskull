package com.flipkart.grayskull.exceptions;

public class GrayskullException extends RuntimeException {

    public GrayskullException(String message) {
        super(message);
    }

    public GrayskullException(String message, Throwable cause) {
        super(message, cause);
    }
}


