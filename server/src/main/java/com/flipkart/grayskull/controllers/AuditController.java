package com.flipkart.grayskull.controllers;

import com.flipkart.grayskull.models.dto.response.AuditEntriesResponse;
import com.flipkart.grayskull.models.dto.response.ResponseTemplate;
import com.flipkart.grayskull.service.interfaces.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * REST controller for audit-related operations.
 * Provides endpoints to query audit entries for projects.
 */
@RestController
@RequestMapping("/v1/audit")
@RequiredArgsConstructor
@Validated
public class AuditController {

    private final AuditService auditService;

    /**
     * Retrieves audit entries for a specific project with optional filtering.
     * 
     * @param projectId Project ID from the path
     * @param resourceName Optional resource name to filter audit entries
     * @param resourceType Optional resource type to filter audit entries (e.g., "SECRET", "PROJECT")
     * @param action Optional action to filter audit entries (e.g., "CREATE", "READ", "UPDATE", "DELETE")
     * @param offset Pagination offset (default: 0)
     * @param limit Pagination limit (default: 10, max: 100)
     * @return ResponseTemplate containing list of audit entries
     */
    @Operation(summary = "Retrieves audit entries for a project with optional filtering by resource name, type, and action")
    @GetMapping("/projects/{projectId}")
    @PreAuthorize("@grayskullSecurity.hasPermission(#projectId, 'audit.read')")
    public ResponseTemplate<AuditEntriesResponse> getProjectAudits(
            @PathVariable("projectId") @Size(max = 255) String projectId,
            @RequestParam(name = "resourceName", required = false) @Size(max = 500) String resourceName,
            @RequestParam(name = "resourceType", required = false) @Size(max = 100) String resourceType,
            @RequestParam(name = "action", required = false) @Size(max = 100) String action,
            @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset,
            @RequestParam(name = "limit", defaultValue = "10") @Min(1) @Max(100) int limit) {
        
        AuditEntriesResponse response = auditService.getAuditEntries(Optional.of(projectId), Optional.ofNullable(resourceName), Optional.ofNullable(resourceType), Optional.ofNullable(action), offset, limit);
        
        return ResponseTemplate.success(response, "Successfully retrieved audit entries.");
    }

    /**
     * Retrieves audit entries across all projects with optional filtering.
     * Requires global admin permission.
     * 
     * @param projectId Optional project ID to filter audit entries
     * @param resourceName Optional resource name to filter audit entries
     * @param resourceType Optional resource type to filter audit entries (e.g., "SECRET", "PROJECT")
     * @param action Optional action to filter audit entries (e.g., "CREATE", "READ", "UPDATE", "DELETE")
     * @param offset Pagination offset (default: 0)
     * @param limit Pagination limit (default: 10, max: 100)
     * @return ResponseTemplate containing list of audit entries
     */
    @Operation(summary = "Retrieves audit entries across all projects with optional filtering")
    @GetMapping
    @PreAuthorize("@grayskullSecurity.hasPermission('audit.read')")
    public ResponseTemplate<AuditEntriesResponse> getAllAudits(
            @RequestParam(name = "projectId", required = false) @Size(max = 255) String projectId,
            @RequestParam(name = "resourceName", required = false) @Size(max = 500) String resourceName,
            @RequestParam(name = "resourceType", required = false) @Size(max = 100) String resourceType,
            @RequestParam(name = "action", required = false) @Size(max = 100) String action,
            @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset,
            @RequestParam(name = "limit", defaultValue = "10") @Min(1) @Max(100) int limit) {
        
        AuditEntriesResponse response = auditService.getAuditEntries(Optional.ofNullable(projectId), Optional.ofNullable(resourceName), Optional.ofNullable(resourceType), Optional.ofNullable(action), offset, limit);
        
        return ResponseTemplate.success(response, "Successfully retrieved audit entries.");
    }

}
