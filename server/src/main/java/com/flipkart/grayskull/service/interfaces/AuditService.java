package com.flipkart.grayskull.service.interfaces;

import com.flipkart.grayskull.audit.AuditAction;
import com.flipkart.grayskull.audit.UserType;
import com.flipkart.grayskull.models.dto.response.AuditEntriesResponse;
import com.flipkart.grayskull.models.dto.response.AuditPageResponse;
import com.flipkart.grayskull.spi.models.AuditQuery;

import java.util.Date;
import java.util.Optional;

/**
 * Service interface for audit-related operations.
 */
public interface AuditService {

    /**
     * Retrieves audit entries with optional filtering and time-range pagination.
     * 
     * @param projectId Optional project ID filter
     * @param resourceName Optional resource name filter
     * @param resourceType Optional resource type filter
     * @param action Optional action filter
     * @param userType Optional user type filter (SERVICE or HUMAN)
     * @param afterTimestamp Optional timestamp filter (entries after this time)
     * @param offset Pagination offset
     * @param limit Pagination limit
     * @return AuditEntriesResponse containing filtered audit entries
     */
    AuditEntriesResponse getAuditEntries(Optional<String> projectId, Optional<String> resourceName, Optional<String> resourceType, Optional<AuditAction> action, Optional<UserType> userType, Optional<Date> afterTimestamp, int offset, int limit);

    /**
     * One page of audit entries, newest first. Audits reach MySQL asynchronously, so the newest minute or two may
     * still be filling in (eventually consistent).
     *
     * @param query  filters
     * @param cursor {@code nextCursor} of the previous page, or null for the first page
     * @param limit  page size
     */
    AuditPageResponse getAuditPage(AuditQuery query, String cursor, int limit);
}

