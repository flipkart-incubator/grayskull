package com.flipkart.grayskull.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.flipkart.grayskull.spi.models.SecretProvider;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Getter
@Setter
@EqualsAndHashCode(callSuper = true)
@SuperBuilder(toBuilder = true)
@Document(collection = "secretProvider")
@CompoundIndex(unique = true, useGeneratedName = true, def = "{'name': 1}")
@NoArgsConstructor
public class SecretProviderEntity extends SecretProvider {
    @Id
    @JsonIgnore
    private String id;

    @Override
    @CreatedDate
    public Instant getCreationTime() {
        return super.getCreationTime();
    }

    @Override
    @LastModifiedDate
    public Instant getUpdatedTime() {
        return super.getUpdatedTime();
    }

}
