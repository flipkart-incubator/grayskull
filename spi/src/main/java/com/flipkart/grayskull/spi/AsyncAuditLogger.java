package com.flipkart.grayskull.spi;

import com.flipkart.grayskull.models.db.AuditEntry;

/**
 * Grayskull audits all the write operations in the same transaction so that none of them are missed.
 * But for reads if we are writing audits in sync same API call then that might reduce overall read throughput.
 * So this class is meant for doing the audit logs for reads and any other APIs where it is detrimental to audit immediately.
 * Implementations of this class can choose audit however they want. So possible implementations could be:
 *   - log to a file
 *   - send audits to DB in a separate thread
 */
public interface AsyncAuditLogger {
    void log(AuditEntry auditEntry);
}
