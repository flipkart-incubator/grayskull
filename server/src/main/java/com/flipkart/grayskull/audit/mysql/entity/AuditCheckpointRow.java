package com.flipkart.grayskull.audit.mysql.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Derby flush progress: one row per pod ({@code derby:<node>}).
 */
@Entity
@Table(name = "audit_checkpoint")
@Getter
@Setter
@NoArgsConstructor
public class AuditCheckpointRow {

    @Id
    @Column(name = "name")
    private String name;

    /** Last Derby row id copied. */
    @Column(name = "last_log_id")
    private Long lastLogId;

    /** Start of the last flush that emptied the pod's buffer; feeds the read-flush lag alert. */
    @Column(name = "flushed_at")
    private Instant flushedAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public AuditCheckpointRow(String name) {
        this.name = name;
    }
}
