package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.models.dto.request.HeartbeatRequest;
import com.flipkart.grayskull.service.interfaces.HeartbeatService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lightweight presence API for SDK clients: records which secrets, at which versions, a workload holds.
 * Unlike {@code POST /v1/secrets/batch}, it reads no secret data, returns nothing and writes no audit entries.
 * <p>
 * {@code /v1/client/heartbeat} is the v2 path. {@code /v1/heartbeat} is kept only so SDK builds made before
 * the rename keep working on playground; remove it before release.
 * </p>
 */
@RestController
@RequestMapping({"/v1/client/heartbeat", "/v1/heartbeat"})
@RequiredArgsConstructor
@Validated
public class HeartbeatController {

    static final String WORKLOAD_HEADER = "Grayskull-Workload";
    static final String USER_AGENT_HEADER = "User-Agent";

    private final HeartbeatService heartbeatService;

    @Operation(summary = "Records the secrets, and their versions, that a workload holds.")
    @PostMapping
    @PreAuthorize("@grayskullSecurity.hasPermissionForHeldSecrets(#request.secrets, 'secrets.read.value')")
    public ResponseEntity<Void> recordHeartbeat(
            @RequestHeader(WORKLOAD_HEADER) @NotBlank @Size(max = 512) String workload,
            @RequestHeader(value = USER_AGENT_HEADER, required = false) String userAgent,
            @Valid @RequestBody HeartbeatRequest request) {

        heartbeatService.recordHeartbeat(workload, userAgent, request.getSecrets());
        return ResponseEntity.noContent().build();
    }
}
