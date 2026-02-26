package com.flipkart.grayskull.service.interfaces;

import com.flipkart.grayskull.models.dto.response.AuditEntriesResponse;

import java.util.Optional;

/**
 * Service interface for audit-related operations.
 */
public interface AuditService {

    /**
     * Retrieves audit entries with optional filtering.
     * 
     * @param projectId Optional project ID filter
     * @param resourceName Optional resource name filter
     * @param resourceType Optional resource type filter
     * @param action Optional action filter
     * @param offset Pagination offset
     * @param limit Pagination limit
     * @return AuditEntriesResponse containing filtered audit entries
     */
    AuditEntriesResponse getAuditEntries(Optional<String> projectId, Optional<String> resourceName, Optional<String> resourceType, Optional<String> action, int offset, int limit);
}

