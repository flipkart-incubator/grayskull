package com.flipkart.grayskull.models.dto.response;

import com.flipkart.grayskull.spi.models.AuditEntry;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One page of the cursor-paged audit API (v2).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditPageResponse {

    /** Newest first. */
    private List<AuditEntry> entries;

    /** Pass back as {@code cursor} to get the next page; null on the last page. */
    private String nextCursor;

    private boolean hasMore;
}
