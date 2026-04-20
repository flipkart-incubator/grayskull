package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.audit.AuditAction;
import com.flipkart.grayskull.audit.AuditConstants;
import com.flipkart.grayskull.audit.utils.RequestUtils;
import com.flipkart.grayskull.models.dto.request.BatchGetSecretsRequest;
import com.flipkart.grayskull.models.dto.response.BatchGetSecretsResponse;
import com.flipkart.grayskull.models.dto.response.BatchSecretItem;
import com.flipkart.grayskull.models.dto.response.ResponseTemplate;
import com.flipkart.grayskull.service.interfaces.SecretService;
import com.flipkart.grayskull.spi.AsyncAuditLogger;
import com.flipkart.grayskull.spi.AuditMetadataEnhancer;
import com.flipkart.grayskull.spi.authn.GrayskullAuthentication;
import com.flipkart.grayskull.spi.models.AuditEntry;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/v1/secrets")
@RequiredArgsConstructor
@Validated
public class SecretBatchController {

    private final SecretService secretService;
    private final AsyncAuditLogger asyncAuditLogger;
    private final RequestUtils requestUtils;
    private final List<AuditMetadataEnhancer> auditMetadataEnhancers;

    @Operation(summary = "Returns current values for secrets whose versions have changed since the caller's last known versions.")
    @PostMapping("/batch")
    @PreAuthorize("@grayskullSecurity.hasPermissionForSecrets(#request.secrets, 'secrets.read.value')")
    public ResponseTemplate<BatchGetSecretsResponse> batchGetSecrets(
            @Valid @RequestBody BatchGetSecretsRequest request,
            HttpServletRequest httpRequest) {

        BatchGetSecretsResponse response = secretService.batchGetSecrets(request.getSecrets());

        if (!response.getUpdatedSecrets().isEmpty()) {
            logAudit(response, httpRequest);
        }

        return ResponseTemplate.success(response, "Success");
    }

    private void logAudit(BatchGetSecretsResponse response, HttpServletRequest httpRequest) {
        GrayskullAuthentication authentication =
                (GrayskullAuthentication) SecurityContextHolder.getContext().getAuthentication();

        // Base metadata collected once from every registered AuditMetadataEnhancer and shared
        // across the per-secret entries produced for this batch. In particular, the SDK-supplied
        // X-Request-Id header is surfaced here via RequestIdAuditEnhancer -> "RequestId" key, so
        // every audit entry for one batch call shares the same RequestId. This is what lets SIEM
        // tooling correlate "which secrets were updated together in the same poll".
        Map<String, String> enhancerMetadata = new HashMap<>();
        auditMetadataEnhancers.stream()
                .map(enhancer -> enhancer.getAdditionalMetadata(httpRequest))
                .filter(Objects::nonNull)
                .forEach(enhancerMetadata::putAll);

        String userId = authentication.getName();
        String actorId = authentication.getActor();
        Map<String, String> ips = requestUtils.getRemoteIPs();

        for (BatchSecretItem secret : response.getUpdatedSecrets()) {
            // Fresh map per entry so the publicPart of one secret doesn't bleed into the
            // metadata of the next.
            Map<String, String> metadata = new HashMap<>(enhancerMetadata);
            metadata.put("publicPart", secret.getPublicPart());

            AuditEntry auditEntry = AuditEntry.builder()
                    .projectId(secret.getProjectId())
                    .resourceType(AuditConstants.RESOURCE_TYPE_SECRET)
                    .resourceName(secret.getSecretName())
                    .resourceVersion(secret.getDataVersion())
                    .action(AuditAction.BATCH_GET_SECRETS.name())
                    .userId(userId)
                    .actorId(actorId)
                    .ips(ips)
                    .metadata(metadata)
                    .build();

            asyncAuditLogger.log(auditEntry);
        }
    }
}
