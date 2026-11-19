package com.flipkart.grayskull.models.dto.request;

import com.flipkart.grayskull.audit.AuditAction;
import com.flipkart.grayskull.audit.UserType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Query parameters of the cursor-paged audit API (v2).
 */
@Data
public class AuditPageRequest {

    /** Only used by the cross-project endpoint; the project endpoint takes it from the path. */
    @Size(max = 255)
    private String projectId;

    @Size(max = 500)
    private String resourceName;

    @Size(max = 100)
    private String resourceType;

    private AuditAction action;

    private UserType userType;

    /** ISO-8601 instant, inclusive. */
    private String from;

    /** ISO-8601 instant, exclusive. */
    private String to;

    /** {@code nextCursor} of the previous page. */
    @Size(max = 512)
    private String cursor;

    @Min(1)
    @Max(50)
    private int limit = 50;
}
