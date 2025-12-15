package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.audit.AuditAction;
import com.flipkart.grayskull.audit.AuditConstants;
import com.flipkart.grayskull.audit.utils.RequestUtils;
import com.flipkart.grayskull.models.dto.request.CreateSecretRequest;
import com.flipkart.grayskull.models.dto.request.UpgradeSecretDataRequest;
import com.flipkart.grayskull.models.dto.response.*;
import com.flipkart.grayskull.spi.MetadataValidator;
import com.flipkart.grayskull.spi.models.AuditEntry;
import com.flipkart.grayskull.spi.models.enums.LifecycleState;
import com.flipkart.grayskull.service.interfaces.SecretService;
import com.flipkart.grayskull.spi.AsyncAuditLogger;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/v1/projects/{projectId}/secrets")
@RequiredArgsConstructor
@Validated
public class SecretController {

    private final SecretService secretService;
    private final AsyncAuditLogger asyncAuditLogger;
    private final RequestUtils requestUtils;
    private final List<MetadataValidator> metadataValidators;

    @Operation(summary = "Lists secrets for a given project with pagination. Always returns the latest version of the secret.")
    @GetMapping
    @PreAuthorize("@grayskullSecurity.ensureEmptyActor() and @grayskullSecurity.hasPermission(#projectId, 'secrets.list')")
    public ResponseTemplate<ListSecretsResponse> listSecrets(
            @PathVariable("projectId") @NotBlank @Size(max = 255) String projectId,
            @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset,
            @RequestParam(name = "limit", defaultValue = "10") @Min(1) @Max(100) int limit) {
        ListSecretsResponse response = secretService.listSecrets(projectId, offset, limit);
        return ResponseTemplate.success(response, "Successfully listed secrets.");
    }

    @Operation(summary = "Creates a new secret for a given project.")
    @PostMapping
    @PreAuthorize("@grayskullSecurity.checkProviderAuthorization(#request.getProvider()) and @grayskullSecurity.hasPermission(#projectId, 'secrets.create')")
    public ResponseTemplate<SecretResponse> createSecret(
            @PathVariable("projectId") @NotBlank @Size(max = 255) String projectId,
            @Valid @RequestBody CreateSecretRequest request) {
        metadataValidators.forEach(plugin -> plugin.validateMetadata(request.getProvider(), request.getProviderMeta()));
        SecretResponse response = secretService.createSecret(projectId, request);
        return ResponseTemplate.success(response, "Successfully created secret.");
    }

    @Operation(summary = "Reads the metadata of a specific secret. Always returns the latest version of the secret.")
    @GetMapping("/{secretName}")
    @PreAuthorize("@grayskullSecurity.hasPermission(#projectId, #secretName, 'secrets.read.metadata')")
    public ResponseTemplate<SecretMetadata> readSecretMetadata(
            @PathVariable("projectId") @NotBlank @Size(max = 255) String projectId,
            @PathVariable("secretName") @NotBlank @Size(max = 255) String secretName) {
        SecretMetadata response = secretService.readSecretMetadata(projectId, secretName);
        return ResponseTemplate.success(response, "Successfully read secret metadata.");
    }

    @Operation(summary = "Reads the value of a specific secret. Always returns the latest version of the secret.")
    @GetMapping("/{secretName}/data")
    @PreAuthorize("@grayskullSecurity.hasPermission(#projectId, #secretName, 'secrets.read.value')")
    public ResponseTemplate<SecretDataResponse> readSecretValue(
            @PathVariable("projectId") @NotBlank @Size(max = 255) String projectId,
            @PathVariable("secretName") @NotBlank @Size(max = 255) String secretName) {
        SecretDataResponse response = secretService.readSecretValue(projectId, secretName);
        Map<String, String> auditMetadata = new HashMap<>();
        auditMetadata.put("publicPart", response.getPublicPart());
        String userId = SecurityContextHolder.getContext().getAuthentication().getName();
        AuditEntry auditEntry = AuditEntry.builder()
                .projectId(projectId)
                .resourceType(AuditConstants.RESOURCE_TYPE_SECRET)
                .resourceName(secretName)
                .resourceVersion(response.getDataVersion())
                .action(AuditAction.READ_SECRET.name())
                .userId(userId)
                .ips(requestUtils.getRemoteIPs())
                .metadata(auditMetadata).build();
        asyncAuditLogger.log(auditEntry);
        return ResponseTemplate.success(response, "Successfully read secret value.");
    }

    @Operation(summary = "Upgrades the data of an existing secret, creating a new version.")
    @PostMapping("/{secretName}/data")
    @PreAuthorize("@grayskullSecurity.hasPermission(#projectId, #secretName, 'secrets.update')")
    public ResponseTemplate<UpgradeSecretDataResponse> upgradeSecretData(
            @PathVariable("projectId") @NotBlank @Size(max = 255) String projectId,
            @PathVariable("secretName") @NotBlank @Size(max = 255) String secretName,
            @Valid @RequestBody UpgradeSecretDataRequest request) {
        UpgradeSecretDataResponse response = secretService.upgradeSecretData(projectId, secretName, request);
        return ResponseTemplate.success(response, "Successfully upgraded secret data.");
    }

    @Operation(summary = "Disables a secret, marking it as soft-deleted.")
    @DeleteMapping("/{secretName}")
    @PreAuthorize("@grayskullSecurity.hasPermission(#projectId, #secretName, 'secrets.delete')")
    public ResponseTemplate<Void> deleteSecret(@PathVariable("projectId") @NotBlank @Size(max = 255) String projectId,
            @PathVariable("secretName") @NotBlank @Size(max = 255) String secretName) {
        secretService.deleteSecret(projectId, secretName);
        return ResponseTemplate.success("Successfully deleted secret.");
    }

    @Operation(summary = "Retrieves a specific version of a secret's data. Its an Admin API.")
    @GetMapping("/{secretName}/versions/{version}")
    @PreAuthorize("@grayskullSecurity.hasPermission(#projectId, #secretName, 'secrets.read.version')")
    public ResponseTemplate<SecretDataVersionResponse> getSecretDataVersion(
            @PathVariable("projectId") @NotBlank @Size(max = 255) String projectId,
            @PathVariable("secretName") @NotBlank @Size(max = 255) String secretName,
            @PathVariable("version") @Min(1) int version,
            @RequestParam(name = "state", required = false) Optional<LifecycleState> state) {
        SecretDataVersionResponse response = secretService.getSecretDataVersion(projectId, secretName, version, state);
        Map<String, String> auditMetadata = new HashMap<>();
        auditMetadata.put("publicPart", response.getPublicPart());
        String userId = SecurityContextHolder.getContext().getAuthentication().getName();
        AuditEntry auditEntry = AuditEntry.builder()
                .projectId(projectId)
                .resourceType(AuditConstants.RESOURCE_TYPE_SECRET)
                .resourceName(secretName)
                .resourceVersion(response.getDataVersion())
                .action(AuditAction.READ_SECRET_VERSION.name())
                .userId(userId)
                .ips(requestUtils.getRemoteIPs())
                .metadata(auditMetadata).build();
        asyncAuditLogger.log(auditEntry);
        return ResponseTemplate.success(response, "Successfully retrieved secret version.");
    }
}
