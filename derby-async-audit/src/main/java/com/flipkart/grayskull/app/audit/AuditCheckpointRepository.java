package com.flipkart.grayskull.app.audit;

import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface AuditCheckpointRepository extends CrudRepository<AuditCheckpoint, Long> {
    Optional<AuditCheckpoint> findByNodeName(String nodeName);
}