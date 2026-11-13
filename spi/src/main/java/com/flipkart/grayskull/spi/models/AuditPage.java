package com.flipkart.grayskull.spi.models;

import java.util.List;

/**
 * One page of audit entries, newest first.
 *
 * @param entries    the entries in this page
 * @param nextCursor position to continue from, or null when there are no more entries
 * @param hasMore    whether another page exists
 */
public record AuditPage(List<AuditEntry> entries, AuditCursor nextCursor, boolean hasMore) { }
