package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.models.dto.request.CreateSecretRequest;
import com.flipkart.grayskull.models.dto.request.UpgradeSecretDataRequest;
import com.flipkart.grayskull.models.dto.response.CreateSecretResponse;
import com.flipkart.grayskull.models.dto.response.ListSecretsResponse;
import com.flipkart.grayskull.models.dto.response.ResponseTemplate;
import com.flipkart.grayskull.models.dto.response.SecretDataResponse;
import com.flipkart.grayskull.models.dto.response.SecretDataVersionResponse;
import com.flipkart.grayskull.models.dto.response.SecretMetadata;
import com.flipkart.grayskull.models.dto.response.UpgradeSecretDataResponse;
import com.flipkart.grayskull.service.interfaces.SecretService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/v1/project/{projectId}/secrets")
@RequiredArgsConstructor
@Validated
public class SecretController {

    private final SecretService secretService;

    @Operation(summary = "Lists secrets for a given project with pagination. Always returns the latest version of the secret.")
    @GetMapping
    @PreAuthorize("hasPermission(#projectId, T(com.flipkart.grayskull.models.authz.GrayskullActions).LIST_SECRETS)")
    public ResponseTemplate<ListSecretsResponse> listSecrets(@PathVariable("projectId") @NotBlank @Size(max = 255) String projectId,
                                                             @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset,
                                                             @RequestParam(name = "limit", defaultValue = "10") @Min(1) @Max(100) int limit) {
        ListSecretsResponse response = secretService.listSecrets(projectId, offset, limit);
        return ResponseTemplate.success(response, "Successfully listed secrets.");
    }

    @Operation(summary = "Creates a new secret for a given project.")
    @PostMapping
    @PreAuthorize("hasPermission(#projectId, T(com.flipkart.grayskull.models.authz.GrayskullActions).CREATE_SECRET)")
    public ResponseTemplate<CreateSecretResponse> createSecret(@PathVariable("projectId") @NotBlank @Size(max = 255) String projectId, @Valid @RequestBody CreateSecretRequest request) {
        CreateSecretResponse response = secretService.createSecret(projectId, request);
        return ResponseTemplate.success(response, "Successfully created secret.");
    }

    @Operation(summary = "Reads the metadata of a specific secret. Always returns the latest version of the secret.")
    @GetMapping("/{secretName}")
    @PreAuthorize("hasPermission(#projectId, T(com.flipkart.grayskull.models.authz.GrayskullActions).READ_SECRET_METADATA)")
    public ResponseTemplate<SecretMetadata> readSecretMetadata(@PathVariable("projectId") @NotBlank @Size(max = 255) String projectId, @PathVariable("secretName") @NotBlank @Size(max = 255) String secretName) {
        SecretMetadata response = secretService.readSecretMetadata(projectId, secretName);
        return ResponseTemplate.success(response, "Successfully read secret metadata.");
    }

    @Operation(summary = "Reads the value of a specific secret. Always returns the latest version of the secret.")
    @GetMapping("/{secretName}/data")
    @PreAuthorize("hasPermission(#projectId, T(com.flipkart.grayskull.models.authz.GrayskullActions).READ_SECRET_VALUE)")
    public ResponseTemplate<SecretDataResponse> readSecretValue(@PathVariable("projectId") @NotBlank @Size(max = 255) String projectId, @PathVariable("secretName") @NotBlank @Size(max = 255) String secretName) {
        SecretDataResponse response = secretService.readSecretValue(projectId, secretName);
        return ResponseTemplate.success(response, "Successfully read secret value.");
    }

    @Operation(summary = "Upgrades the data of an existing secret, creating a new version.")
    @PostMapping("/{secretName}/data")
    @PreAuthorize("hasPermission(#projectId, T(com.flipkart.grayskull.models.authz.GrayskullActions).ADD_SECRET_VERSION)")
    public ResponseTemplate<UpgradeSecretDataResponse> upgradeSecretData(@PathVariable("projectId") @NotBlank @Size(max = 255) String projectId, @PathVariable("secretName") @NotBlank @Size(max = 255) String secretName, @Valid @RequestBody UpgradeSecretDataRequest request) {
        UpgradeSecretDataResponse response = secretService.upgradeSecretData(projectId, secretName, request);
        return ResponseTemplate.success(response, "Successfully upgraded secret data.");
    }

    @Operation(summary = "Deletes a secret from a project. Deletes all the versions of the secret.")
    @DeleteMapping("/{secretName}")
    @PreAuthorize("hasPermission(#projectId, T(com.flipkart.grayskull.models.authz.GrayskullActions).DELETE_SECRET)")
    public ResponseTemplate<Void> deleteSecret(@PathVariable("projectId") @NotBlank @Size(max = 255) String projectId, @PathVariable("secretName") @NotBlank @Size(max = 255) String secretName) {
        secretService.deleteSecret(projectId, secretName);
        return ResponseTemplate.success("Successfully deleted secret.");
    }

    @Operation(summary = "Retrieves a specific version of a secret's data. Its an Admin API.")
    @GetMapping("/{secretName}/versions/{version}")
    @PreAuthorize("hasPermission(#projectId, T(com.flipkart.grayskull.models.authz.GrayskullActions).READ_SECRET_VERSION_VALUE)")
    public ResponseTemplate<SecretDataVersionResponse> getSecretDataVersion(@PathVariable("projectId") @NotBlank @Size(max = 255) String projectId,
                                                                            @PathVariable("secretName") @NotBlank @Size(max = 255) String secretName,
                                                                            @PathVariable("version") @Min(1) int version) {
        SecretDataVersionResponse response = secretService.getSecretDataVersion(projectId, secretName, version);
        return ResponseTemplate.success(response, "Successfully retrieved secret version.");
    }
} 