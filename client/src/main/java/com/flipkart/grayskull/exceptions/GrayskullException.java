package com.flipkart.grayskull.exceptions;

import lombok.Getter;

@Getter
public class GrayskullException extends RuntimeException {
    private int statusCode;

    public GrayskullException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public GrayskullException(int statusCode, String message, Exception e) {
        super(message, e);
        this.statusCode = statusCode;
    }

    public GrayskullException(int statusCode, Exception e) {
        super(e);
        this.statusCode = statusCode;
    }

    public GrayskullException(String message) {
        super(message);
        this.statusCode = 0;
    }

    public GrayskullException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }
}


