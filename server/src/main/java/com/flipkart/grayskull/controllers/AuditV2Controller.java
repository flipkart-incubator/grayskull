package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.models.dto.request.AuditPageRequest;
import com.flipkart.grayskull.models.dto.response.AuditPageResponse;
import com.flipkart.grayskull.models.dto.response.ResponseTemplate;
import com.flipkart.grayskull.service.interfaces.AuditService;
import com.flipkart.grayskull.spi.models.AuditQuery;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * Cursor-paged audit API, newest first. Pass {@code nextCursor} back as {@code cursor} to get the next page.
 * Audits reach MySQL asynchronously, so results are eventually consistent: an audit can appear a minute or two late.
 */
@RestController
@RequestMapping("/v2/audit")
@RequiredArgsConstructor
@Validated
public class AuditV2Controller {

    private final AuditService auditService;

    @Operation(summary = "Retrieves a page of audit entries for a project, newest first, using cursor paging")
    @GetMapping("/projects/{projectId}")
    @PreAuthorize("@grayskullSecurity.hasPermission(#projectId, 'audit.read')")
    public ResponseTemplate<AuditPageResponse> getProjectAudits(@PathVariable("projectId") @Size(max = 255) String projectId,
                                                                @Valid AuditPageRequest request) {
        AuditQuery query = new AuditQuery(projectId, request.getResourceName(), request.getResourceType(), name(request.getAction()),
                name(request.getUserType()), parse(request.getFrom()), parse(request.getTo()));
        return ResponseTemplate.success(auditService.getAuditPage(query, request.getCursor(), request.getLimit()), "Successfully retrieved audit entries.");
    }

    @Operation(summary = "Retrieves a page of audit entries across all projects, newest first, using cursor paging")
    @GetMapping
    @PreAuthorize("@grayskullSecurity.hasPermission('audit.read')")
    public ResponseTemplate<AuditPageResponse> getAllAudits(@Valid AuditPageRequest request) {
        AuditQuery query = new AuditQuery(request.getProjectId(), request.getResourceName(), request.getResourceType(), name(request.getAction()),
                name(request.getUserType()), parse(request.getFrom()), parse(request.getTo()));
        return ResponseTemplate.success(auditService.getAuditPage(query, request.getCursor(), request.getLimit()), "Successfully retrieved audit entries.");
    }

    private static Instant parse(String timestamp) {
        return timestamp == null ? null : Instant.parse(timestamp);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
