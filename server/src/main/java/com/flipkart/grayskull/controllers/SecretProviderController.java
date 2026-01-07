package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.models.dto.request.CreateSecretProviderRequest;
import com.flipkart.grayskull.models.dto.request.SecretProviderRequest;
import com.flipkart.grayskull.service.interfaces.SecretProviderService;
import com.flipkart.grayskull.spi.models.SecretProvider;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

@RestController
@RequestMapping("/v1/providers")
@AllArgsConstructor
@Validated
@Slf4j
public class SecretProviderController {

    private final SecretProviderService secretProviderService;

    @GetMapping
    @PreAuthorize("@grayskullSecurity.hasPermission('providers.list')")
    public ResponseEntity<List<SecretProvider>> listProviders() {
        log.debug("Received request to list all secret providers");
        
        List<SecretProvider> response = secretProviderService.listProviders();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{name}")
    @PreAuthorize("@grayskullSecurity.hasPermission('providers.read')")
    public ResponseEntity<SecretProvider> getProvider(@PathVariable @NotBlank String name) {
        log.debug("Received request to get secret provider: {}", name);
        
        SecretProvider response = secretProviderService.getProvider(name);
        return ResponseEntity.ok(response);
    }

    @PostMapping
    @PreAuthorize("@grayskullSecurity.hasPermission('providers.create')")
    public ResponseEntity<SecretProvider> createProvider(@Valid @RequestBody CreateSecretProviderRequest request) {
        log.debug("Received request to create secret provider: {}", request.getName());
        validateAuthAttributes(request);

        SecretProvider response = secretProviderService.createProvider(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private void validateAuthAttributes(SecretProviderRequest request) {
        if (!request.getAuthMechanism().getAttributesClass().isInstance(request.getAuthAttributes())) {
            throw new IllegalArgumentException("Invalid auth attributes for auth mechanism: " + request.getAuthMechanism());
        }
    }


    @PutMapping("/{name}")
    @PreAuthorize("@grayskullSecurity.hasPermission('providers.update')")
    public ResponseEntity<SecretProvider> updateProvider(@PathVariable @NotBlank String name, @Valid @RequestBody SecretProviderRequest request) {
        log.debug("Received request to update secret provider: {}", name);
        validateAuthAttributes(request);

        SecretProvider response = secretProviderService.updateProvider(name, request);
        return ResponseEntity.ok(response);
    }
}
