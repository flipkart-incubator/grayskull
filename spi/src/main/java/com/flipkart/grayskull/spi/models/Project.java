package com.flipkart.grayskull.spi.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Represents a Project in the Grayskull system's resource hierarchy.
 * Projects are typically organizational units or application identifiers (e.g.,
 * from a Resource Hierarchy Service)
 * to which secrets can be associated. They form a hierarchical structure.
 * 
 * This is a plain POJO contract that the server module will implement with framework-specific annotations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class Project {

    /**
     * The unique identifier for the project.
     * This is the primary key.
     */
    private String id;

    /**
     * The identifier of the Key Management Service (KMS) key associated with this project.
     * This key may be used as a default for encrypting secrets under this project,
     * or for project-specific configurations.
     */
    private String kmsKeyId;
}
