package com.flipkart.grayskull.app.audit;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Slf4j
@AllArgsConstructor
public class DerbyStaleDataCleaner {
    private final AuditCheckpointRepository auditCheckpointRepository;
    private final AuditProperties auditProperties;

    public void cleanStaleData() throws IOException {
        if (auditProperties.getStalenessDuration().compareTo(Duration.ZERO) <= 0) {
            log.info("staleness duration is explicitly set to less than or equal to zero so not deleting any data");
            return;
        }
        Optional<AuditCheckpoint> checkpoint = auditCheckpointRepository.findByNodeName(auditProperties.getNodeName());
        if (checkpoint.isEmpty()) {
            log.info("Checkpoint not present so not deleting any data");
            return;
        }
        if (!checkpoint.get().getLastModifiedAt().isBefore(Instant.now().minus(auditProperties.getStalenessDuration()))) {
            log.info("checkpoint is not stale so not deleting the data");
            return;
        }
        log.info("checkpoint is stale, deleting the data");
        auditCheckpointRepository.deleteByNodeName(auditProperties.getNodeName());
        Path derbyPath = Path.of(auditProperties.getDerbyDirectory()).normalize().toAbsolutePath();
        // Validate it's not current dir, or one of parent dirs
        if (Path.of("").toAbsolutePath().startsWith(derbyPath)) {
            throw new IllegalStateException("Invalid Derby directory path: " + derbyPath);
        }
        if (Files.exists(derbyPath)) {
            FileSystemUtils.deleteRecursively(derbyPath);
        }
    }
}
