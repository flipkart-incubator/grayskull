package com.flipkart.grayskull.models.dto.response;

import com.flipkart.grayskull.spi.models.AuditEntry;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for audit entries.
 * Contains a list of audit entries and pagination information.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEntriesResponse {
    
    /**
     * List of audit entries matching the query criteria.
     */
    private List<AuditEntry> entries;
    
    /**
     * Total number of audit entries matching the criteria (before pagination).
     */
    private long total;
}

