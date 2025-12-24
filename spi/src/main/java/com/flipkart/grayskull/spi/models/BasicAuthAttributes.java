package com.flipkart.grayskull.spi.models;

import com.flipkart.grayskull.spi.EncryptionService;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public final class BasicAuthAttributes extends EncryptableValue {
    @NotNull
    @Size(max = 50)
    private String username;
    @NotNull
    @Size(max = 100)
    private String password;

    @Override
    protected void encrypt(EncryptionService encryptionService, String kmsKeyId) {
        this.password = encryptionService.encrypt(this.password, kmsKeyId);
    }

    @Override
    protected void decrypt(EncryptionService encryptionService, String kmsKeyId) {
        this.password = encryptionService.decrypt(this.password, kmsKeyId);
    }
}
