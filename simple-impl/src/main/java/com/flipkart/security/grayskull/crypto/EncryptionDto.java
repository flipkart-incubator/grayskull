package com.flipkart.security.grayskull.crypto;

import jakarta.validation.constraints.NotEmpty;

public class EncryptionDto {

    @NotEmpty
    private String encryptionKey;

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }
}
