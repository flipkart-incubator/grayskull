package com.flipkart.security.grayskull.crypto;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/crypto/keys")
public class KeyUnsealController {

    private final ChaChaEncryptionService chaChaEncryptionService;

    public KeyUnsealController(ChaChaEncryptionService chaChaEncryptionService) {
        this.chaChaEncryptionService = chaChaEncryptionService;
    }

    @PostMapping("/unseal")
    public void unsealKey(@RequestBody @Valid EncryptionDto encryptionKey) {
        chaChaEncryptionService.decryptKeys(encryptionKey.getEncryptionKey());
    }
}
