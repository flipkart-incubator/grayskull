package com.flipkart.grayskull.entities;

import com.flipkart.grayskull.spi.models.AuditEntry;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * MongoDB entity implementation for AuditEntry.
 * Extends the SPI contract with Spring Data annotations.
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Document(collection = "auditEntry")

@CompoundIndex(name = "project_timestamp_idx", def = "{'projectId': 1, 'timestamp': -1}")
@CompoundIndex(name = "project_resource_timestamp_idx", def = "{'projectId': 1, 'resourceName': 1, 'timestamp': -1}")
@CompoundIndex(name = "project_type_timestamp_idx", def = "{'projectId': 1, 'resourceType': 1, 'timestamp': -1}")
@CompoundIndex(name = "project_action_timestamp_idx", def = "{'projectId': 1, 'action': 1, 'timestamp': -1}")
public class AuditEntryEntity extends AuditEntry {

    @Id
    @Override
    public String getId() {
        return super.getId();
    }

}
