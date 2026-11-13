package com.flipkart.grayskull.audit.mysql.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Primary key of {@link AuditEntryRow}: the row id plus the timestamp, because MySQL requires the partitioning
 * column ({@code ts}) in every unique key.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditRowId implements Serializable {
    private UUID rowId;
    private Instant ts;
}
