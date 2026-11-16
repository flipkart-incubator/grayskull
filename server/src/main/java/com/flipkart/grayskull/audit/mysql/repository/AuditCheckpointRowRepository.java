package com.flipkart.grayskull.audit.mysql.repository;

import com.flipkart.grayskull.audit.mysql.entity.AuditCheckpointRow;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditCheckpointRowRepository extends JpaRepository<AuditCheckpointRow, String> {
}
