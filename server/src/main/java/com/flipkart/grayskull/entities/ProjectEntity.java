package com.flipkart.grayskull.entities;

import com.flipkart.grayskull.spi.models.Project;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * MongoDB entity implementation for Project.
 * Extends the SPI contract with Spring Data annotations.
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Document(collection = "project")
public class ProjectEntity extends Project {

    @Id
    @Override
    public String getId() {
        return super.getId();
    }
}
