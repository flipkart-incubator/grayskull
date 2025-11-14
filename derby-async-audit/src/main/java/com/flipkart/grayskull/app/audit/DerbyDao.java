package com.flipkart.grayskull.app.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.spi.models.AuditEntry;
import io.micrometer.core.annotation.Counted;
import io.micrometer.core.annotation.Timed;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.*;

@Timed
@Counted
@Slf4j
public class DerbyDao {
    private static final String TABLE_ALREADY_EXISTS = "X0Y32";

    private final Connection connection;
    private final ObjectMapper objectMapper;

    public DerbyDao(String derbyUrl, ObjectMapper objectMapper) throws SQLException {
        this.connection = DriverManager.getConnection(derbyUrl);
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE audits (id BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY(START WITH 1, INCREMENT BY 1), event LONG VARCHAR)");
        } catch (SQLException e) {
            if (!TABLE_ALREADY_EXISTS.equals(e.getSQLState())) {
                throw e;
            }
        }
    }

    @PreDestroy
    public void cleanup() throws SQLException {
        connection.close();
    }

    public void insertAuditEntry(AuditEntry auditEntry) throws SQLException, JsonProcessingException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO audits (event) VALUES (?)")) {
            String eventString = objectMapper.writeValueAsString(auditEntry);
            statement.setString(1, eventString);
            statement.execute();
        }
    }

    public void deleteAuditEntries(long maxId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM audits WHERE id <= ?")) {
            statement.setLong(1, maxId);
            statement.execute();
        }
    }

    public Map<Long, AuditEntry> fetchAuditEntries(long maxId, int batchSize) throws SQLException, JsonProcessingException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, event FROM audits WHERE id > ? ORDER BY id FETCH FIRST ? ROWS ONLY")) {
            statement.setLong(1, maxId);
            statement.setInt(2, batchSize);
            statement.execute();
            ResultSet resultSet = statement.getResultSet();
            Map<Long, AuditEntry> auditEntries = new HashMap<>();
            while (resultSet.next()) {
                long id = resultSet.getLong(1);
                String eventString = resultSet.getString(2);
                AuditEntry auditEntry = objectMapper.readValue(eventString, AuditEntry.class);
                auditEntries.put(id, auditEntry);
            }
            return auditEntries;
        }
    }
}
