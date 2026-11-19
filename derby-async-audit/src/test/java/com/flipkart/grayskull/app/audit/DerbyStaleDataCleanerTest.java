package com.flipkart.grayskull.app.audit;

import com.flipkart.grayskull.spi.AuditFlushStore;
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

    private final AuditFlushStore auditFlushStore = mock();

    private final AuditProperties auditProperties = new AuditProperties();

    private final DerbyStaleDataCleaner derbyStaleDataCleaner = new DerbyStaleDataCleaner(auditFlushStore, auditProperties);

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
        when(auditFlushStore.lastCheckpointUpdate(testNodeName)).thenReturn(Optional.empty());

        // Act
        derbyStaleDataCleaner.cleanStaleData();

        // Assert
        verify(auditFlushStore, never()).deleteCheckpoint(anyString());
        assertTrue(Files.exists(testDerbyFolder));
    }

    @Test
    void cleanStaleData_WhenStalenessDurationIsZero_ShouldNotDeleteAnything() throws IOException {
        // Arrange
        when(auditFlushStore.lastCheckpointUpdate(testNodeName)).thenReturn(Optional.of(Instant.now().minus(Duration.ofDays(2))));
        auditProperties.setStalenessDuration(Duration.ZERO);

        // Act
        derbyStaleDataCleaner.cleanStaleData();

        // Assert
        verify(auditFlushStore, never()).deleteCheckpoint(anyString());
        assertTrue(Files.exists(testDerbyFolder));
    }

    @Test
    void cleanStaleData_WhenCheckpointIsNotStale_ShouldNotDeleteAnything() throws IOException {
        // Arrange
        when(auditFlushStore.lastCheckpointUpdate(testNodeName)).thenReturn(Optional.of(Instant.now().minus(Duration.ofHours(12)))); // Within staleness duration

        // Act
        derbyStaleDataCleaner.cleanStaleData();

        // Assert
        verify(auditFlushStore, never()).deleteCheckpoint(anyString());
        assertTrue(Files.exists(testDerbyFolder));
    }

    @Test
    void cleanStaleData_WhenCheckpointIsStale_ShouldDeleteCheckpointAndDerbyFolder() throws IOException {
        // Arrange
        when(auditFlushStore.lastCheckpointUpdate(testNodeName)).thenReturn(Optional.of(Instant.now().minus(Duration.ofDays(2)))); // Older than staleness duration


        // Act
        derbyStaleDataCleaner.cleanStaleData();

        // Assert
        verify(auditFlushStore).deleteCheckpoint(testNodeName);
        assertFalse(Files.exists(testDerbyFolder));

    }
}
