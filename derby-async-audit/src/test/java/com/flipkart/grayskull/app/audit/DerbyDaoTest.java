package com.flipkart.grayskull.app.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.flipkart.grayskull.spi.models.AuditEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DerbyDaoTest {

    private DerbyDao derbyDao;

    @BeforeEach
    void setUp() throws SQLException {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        derbyDao = new DerbyDao("jdbc:derby:memory:testdb;create=true", objectMapper);
        derbyDao.init();
    }

    @AfterEach
    void tearDown() throws SQLException {
        derbyDao.deleteAuditEntries(1000);
    }

    @Test
    void shouldInsertAndFetchAuditEntry() throws Exception {
        AuditEntry entry = createTestAuditEntry("test-id", "CREATE");
        
        derbyDao.insertAuditEntry(entry);
        Map<Long, AuditEntry> fetched = derbyDao.fetchAuditEntries(0, 10);
        
        assertEquals(1, fetched.size());
        AuditEntry fetchedEntry = fetched.values().iterator().next();
        assertEquals("test-id", fetchedEntry.getId());
        assertEquals("CREATE", fetchedEntry.getAction());
    }

    @Test
    void shouldFetchEntriesWithPagination() throws Exception {
        insertMultipleEntries(5);
        
        Map<Long, AuditEntry> firstBatch = derbyDao.fetchAuditEntries(0, 3);
        assertEquals(3, firstBatch.size());
        
        long maxId = firstBatch.keySet().stream().mapToLong(Long::longValue).max().orElse(0L);
        Map<Long, AuditEntry> secondBatch = derbyDao.fetchAuditEntries(maxId, 3);
        assertEquals(2, secondBatch.size());
    }

    @Test
    void shouldDeleteEntriesUpToMaxId() throws Exception {
        insertMultipleEntries(5);
        
        Map<Long, AuditEntry> allEntries = derbyDao.fetchAuditEntries(0, 10);
        assertEquals(5, allEntries.size());
        
        long maxId = allEntries.keySet().stream().sorted().skip(2).findFirst().orElse(0L);
        derbyDao.deleteAuditEntries(maxId);
        
        Map<Long, AuditEntry> remaining = derbyDao.fetchAuditEntries(0, 10);
        assertEquals(2, remaining.size());
    }

    private void insertMultipleEntries(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            AuditEntry entry = createTestAuditEntry("test-id-" + i, "ACTION-" + i);
            derbyDao.insertAuditEntry(entry);
        }
    }

    private AuditEntry createTestAuditEntry(String id, String action) {
        return AuditEntry.builder()
                .id(id)
                .projectId("test-project")
                .resourceType("SECRET")
                .resourceName("test-secret")
                .action(action)
                .userId("test-user")
                .timestamp(Instant.now())
                .build();
    }
}
