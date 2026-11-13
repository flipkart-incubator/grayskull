package com.flipkart.grayskull.audit.mysql.repository;

import com.flipkart.grayskull.audit.mysql.entity.AuditCheckpointRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface AuditCheckpointRowRepository extends JpaRepository<AuditCheckpointRow, String> {

    /**
     * Oldest "fully drained" time among Derby checkpoints of pods active since {@code activeSince}:
     * every read audit created before it is stored in MySQL.
     *
     * @return the time, or null when no active pod has drained yet
     */
    @Query("select min(c.flushedAt) from AuditCheckpointRow c where c.name like 'derby:%' and c.updatedAt >= :activeSince")
    Instant findOldestFlushedAtOfActiveNodes(@Param("activeSince") Instant activeSince);
}
