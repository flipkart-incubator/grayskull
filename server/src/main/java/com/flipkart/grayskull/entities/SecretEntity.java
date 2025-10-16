package com.flipkart.grayskull.entities;

import com.flipkart.grayskull.spi.models.Secret;
import com.flipkart.grayskull.spi.models.SecretData;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Transient;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * MongoDB entity implementation for Secret.
 * Extends the SPI contract with Spring Data annotations and compound indexes for query optimization.
 */
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@Getter
@Document(collection = "secret")
@CompoundIndexes({
        @CompoundIndex(name = "project_name_idx", def = "{'projectId': 1, 'name': 1}", unique = true),
        @CompoundIndex(name = "project_state_idx", def = "{'projectId': 1, 'state': 1}")
})
public class SecretEntity extends Secret {

    @Id
    @Override
    public String getId() {
        return super.getId();
    }

    @Version
    @Override
    public Long getVersion() {
        return super.getVersion();
    }

    @CreatedDate
    @Override
    public Instant getCreationTime() {
        return super.getCreationTime();
    }

    @LastModifiedDate
    @Override
    public Instant getUpdatedTime() {
        return super.getUpdatedTime();
    }

    @Transient
    @Override
    public SecretData getData() {
        return super.getData();
    }
}
