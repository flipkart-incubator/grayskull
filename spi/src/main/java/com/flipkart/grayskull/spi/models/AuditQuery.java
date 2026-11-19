package com.flipkart.grayskull.spi.models;

import java.time.Instant;

/**
 * Filters for a page of audit entries. Every field is optional (null means "no filter").
 *
 * @param projectId    project the audited resource belongs to
 * @param resourceName name of the audited resource, e.g. the secret name
 * @param resourceType type of the audited resource, e.g. "SECRET"
 * @param action       audit action name, e.g. "READ_SECRET"
 * @param userType     "SERVICE" or "HUMAN"; unknown values add no filter
 * @param from         inclusive lower bound on the event timestamp
 * @param to           exclusive upper bound on the event timestamp
 */
public record AuditQuery(String projectId, String resourceName, String resourceType, String action,
                         String userType, Instant from, Instant to) {
}
