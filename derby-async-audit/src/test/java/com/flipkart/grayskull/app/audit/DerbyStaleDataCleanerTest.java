package com.flipkart.grayskull.app.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DerbyStaleDataCleanerTest {

    private final AuditCheckpointRepository auditCheckpointRepository = mock();

    private final AuditProperties auditProperties = new AuditProperties();

    private final DerbyStaleDataCleaner derbyStaleDataCleaner = new DerbyStaleDataCleaner(auditCheckpointRepository, auditProperties);

    private final String testNodeName = "test-node";
    private Path testDerbyFolder = null;
    private final Duration testStalenessDuration = Duration.ofDays(1);

    @BeforeEach
    void setUp() throws IOException {
        testDerbyFolder = Files.createTempDirectory("derby");
        auditProperties.setNodeName(testNodeName);
        auditProperties.setDerbyDirectory(testDerbyFolder.toString());
        auditProperties.setStalenessDuration(testStalenessDuration);
    }

    @Test
    void cleanStaleData_WhenNoCheckpointExists_ShouldNotDeleteAnything() throws IOException {
        // Arrange
        when(auditCheckpointRepository.findByNodeName(testNodeName)).thenReturn(Optional.empty());

        // Act
        derbyStaleDataCleaner.cleanStaleData();

        // Assert
        verify(auditCheckpointRepository, never()).deleteByNodeName(anyString());
        assertTrue(Files.exists(testDerbyFolder));
    }

    @Test
    void cleanStaleData_WhenStalenessDurationIsZero_ShouldNotDeleteAnything() throws IOException {
        // Arrange
        AuditCheckpoint checkpoint = new AuditCheckpoint(testNodeName);
        checkpoint.setLastModifiedAt(Instant.now().minus(Duration.ofDays(2)));

        when(auditCheckpointRepository.findByNodeName(testNodeName)).thenReturn(Optional.of(checkpoint));
        auditProperties.setStalenessDuration(Duration.ZERO);

        // Act
        derbyStaleDataCleaner.cleanStaleData();

        // Assert
        verify(auditCheckpointRepository, never()).deleteByNodeName(anyString());
        assertTrue(Files.exists(testDerbyFolder));
    }

    @Test
    void cleanStaleData_WhenCheckpointIsNotStale_ShouldNotDeleteAnything() throws IOException {
        // Arrange
        AuditCheckpoint checkpoint = new AuditCheckpoint(testNodeName);
        checkpoint.setLastModifiedAt(Instant.now().minus(Duration.ofHours(12))); // Within staleness duration

        when(auditCheckpointRepository.findByNodeName(testNodeName)).thenReturn(Optional.of(checkpoint));

        // Act
        derbyStaleDataCleaner.cleanStaleData();

        // Assert
        verify(auditCheckpointRepository, never()).deleteByNodeName(anyString());
        assertTrue(Files.exists(testDerbyFolder));
    }

    @Test
    void cleanStaleData_WhenCheckpointIsStale_ShouldDeleteCheckpointAndDerbyFolder() throws IOException {
        // Arrange
        AuditCheckpoint checkpoint = new AuditCheckpoint(testNodeName);
        checkpoint.setLastModifiedAt(Instant.now().minus(Duration.ofDays(2))); // Older than staleness duration

        when(auditCheckpointRepository.findByNodeName(testNodeName)).thenReturn(Optional.of(checkpoint));


        // Act
        derbyStaleDataCleaner.cleanStaleData();

        // Assert
        verify(auditCheckpointRepository).deleteByNodeName(testNodeName);
        assertFalse(Files.exists(testDerbyFolder));

    }
}
