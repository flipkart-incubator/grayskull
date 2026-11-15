package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.audit.AuditAction;
import com.flipkart.grayskull.audit.AuditConstants;
import com.flipkart.grayskull.audit.utils.RequestUtils;
import com.flipkart.grayskull.models.dto.request.BatchGetSecretsRequest;
import com.flipkart.grayskull.models.dto.request.SecretVersionEntry;
import com.flipkart.grayskull.models.dto.response.BatchGetSecretsResponse;
import com.flipkart.grayskull.models.dto.response.BatchSecretItem;
import com.flipkart.grayskull.models.dto.response.ResponseTemplate;
import com.flipkart.grayskull.service.interfaces.HeartbeatService;
import com.flipkart.grayskull.service.interfaces.SecretService;
import com.flipkart.grayskull.spi.AsyncAuditLogger;
import com.flipkart.grayskull.spi.authn.GrayskullAuthentication;
import com.flipkart.grayskull.spi.models.AuditEntry;
import io.swagger.v3.oas.annotations.Operation;
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
    private final HeartbeatService heartbeatService;

    @Operation(summary = "Returns current values for secrets whose versions have changed since the caller's last known versions.")
    @PostMapping("/batch")
    @PreAuthorize("@grayskullSecurity.hasPermissionForSecrets(#request.secrets, 'secrets.read.value')")
    public ResponseTemplate<BatchGetSecretsResponse> batchGetSecrets(
            @Valid @RequestBody BatchGetSecretsRequest request,
            @RequestHeader(value = "Grayskull-Workload", required = false) String workload,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {

        BatchGetSecretsResponse response = secretService.batchGetSecrets(request.getSecrets());

        if (!response.getUpdatedSecrets().isEmpty()) {
            logAudit(response);
        }
        recordHookPollSightings(request, response, workload, userAgent);

        return ResponseTemplate.success(response, "Success");
    }

    /**
     * Sightings (HLD: workload headers on SDK requests are deduped and persisted). Refresh-hook pollers hold each
     * polled secret at its {@code lastKnownVersion}, and are being handed any newer version in the response.
     */
    private void recordHookPollSightings(BatchGetSecretsRequest request, BatchGetSecretsResponse response,
                                         String workload, String userAgent) {
        String address = requestUtils.getClientAddress();
        for (SecretVersionEntry entry : request.getSecrets()) {
            Integer held = entry.getLastKnownVersion();
            if (held != null && held > 0) {
                heartbeatService.recordHookPoll(workload, userAgent, address, entry.getProjectId(), entry.getSecretName(), held);
            }
        }
        for (BatchSecretItem served : response.getUpdatedSecrets()) {
            heartbeatService.recordHookPoll(workload, userAgent, address, served.getProjectId(), served.getSecretName(),
                    served.getDataVersion());
        }
    }

    private void logAudit(BatchGetSecretsResponse response) {
        GrayskullAuthentication authentication =
                (GrayskullAuthentication) SecurityContextHolder.getContext().getAuthentication();

        Map<String, String> enhancerMetadata = requestUtils.getAdditionalMetadata();
        String userId = authentication.getName();
        String actorId = authentication.getActor();
        Map<String, String> ips = requestUtils.getRemoteIPs();

        for (BatchSecretItem secret : response.getUpdatedSecrets()) {
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
