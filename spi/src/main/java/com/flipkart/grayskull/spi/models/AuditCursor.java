package com.flipkart.grayskull.spi.models;

import java.time.Instant;

/**
 * Position of the last entry returned in a page. The next page starts strictly after it
 * in (timestamp desc, id desc) order.
 *
 * @param timestamp event timestamp of the last returned entry
 * @param id        storage id of the last returned entry, used as the tie-breaker
 */
public record AuditCursor(Instant timestamp, String id) {
}
