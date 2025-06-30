package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.service.interfaces.SecretService;
import com.flipkart.grayskull.models.dto.request.CreateSecretRequest;
import com.flipkart.grayskull.models.dto.request.UpgradeSecretDataRequest;
import com.flipkart.grayskull.models.dto.response.*;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;


@RestController
@RequestMapping("/v1/project/{projectId}/secrets")
@RequiredArgsConstructor
@Validated
public class SecretController {

    private final SecretService secretService;

    @Operation(summary = "Lists secrets for a given project with pagination. Always returns the latest version of the secret.")
    @GetMapping
    public ListSecretsResponse listSecrets(@PathVariable("projectId") @NotBlank @Size(max = 255) String projectId,
                                           @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset,
                                           @RequestParam(name = "limit", defaultValue = "10") @Min(1) @Max(100) int limit) {
        return secretService.listSecrets(projectId, offset, limit);
    }

    @Operation(summary = "Creates a new secret for a given project.")
    @PostMapping
    public CreateSecretResponse createSecret(@PathVariable("projectId") @NotBlank @Size(max = 255) String projectId, @Valid @RequestBody CreateSecretRequest request) {
        return secretService.createSecret(projectId, request);
    }

    @Operation(summary = "Reads the metadata of a specific secret. Always returns the latest version of the secret.")
    @GetMapping("/{secretName}")
    public SecretMetadata readSecretMetadata(@PathVariable("projectId") @NotBlank @Size(max = 255) String projectId, @PathVariable("secretName") @NotBlank @Size(max = 255) String secretName) {
        return secretService.readSecretMetadata(projectId, secretName);
    }

    @Operation(summary = "Reads the value of a specific secret. Always returns the latest version of the secret.")
    @GetMapping("/{secretName}/data")
    public SecretDataResponse readSecretValue(@PathVariable("projectId") @NotBlank @Size(max = 255) String projectId, @PathVariable("secretName") @NotBlank @Size(max = 255) String secretName) {
        return secretService.readSecretValue(projectId, secretName);
    }

    @Operation(summary = "Upgrades the data of an existing secret, creating a new version.")
    @PostMapping("/{secretName}/data")
    public UpgradeSecretDataResponse upgradeSecretData(@PathVariable("projectId") @NotBlank @Size(max = 255) String projectId, @PathVariable("secretName") @NotBlank @Size(max = 255) String secretName, @Valid @RequestBody UpgradeSecretDataRequest request) {
        return secretService.upgradeSecretData(projectId, secretName, request);
    }

    @Operation(summary = "Deletes a secret from a project. Deletes all the versions of the secret.")
    @DeleteMapping("/{secretName}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSecret(@PathVariable("projectId") @NotBlank @Size(max = 255) String projectId, @PathVariable("secretName") @NotBlank @Size(max = 255) String secretName) {
        secretService.deleteSecret(projectId, secretName);
    }

  
} 