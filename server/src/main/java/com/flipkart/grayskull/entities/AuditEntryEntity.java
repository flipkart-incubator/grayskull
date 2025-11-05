package com.flipkart.grayskull.entities;

import com.flipkart.grayskull.spi.models.AuditEntry;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * MongoDB entity implementation for AuditEntry.
 * Extends the SPI contract with Spring Data annotations.
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Document(collection = "auditEntry")
public class AuditEntryEntity extends AuditEntry {

    @Id
    @Override
    public String getId() {
        return super.getId();
    }

    @CreatedDate
    @Override
    public Instant getTimestamp() {
        return super.getTimestamp();
    }
}
