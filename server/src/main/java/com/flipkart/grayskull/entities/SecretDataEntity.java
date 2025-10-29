package com.flipkart.grayskull.entities;

import com.flipkart.grayskull.spi.models.SecretData;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * MongoDB entity implementation for SecretData.
 * Extends the SPI contract with Spring Data annotations and compound index for efficient version lookups.
 */
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
@Getter
@Document(collection = "secretData")
@CompoundIndex(name = "secret_version_idx", def = "{'secretId': 1, 'dataVersion': 1}", unique = true)
public class SecretDataEntity extends SecretData {

    @Id
    @Override
    public String getId() {
        return super.getId();
    }
}
