package com.flipkart.grayskull.models.db;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;

/**
 * Represents a Project in the Grayskull system's resource hierarchy.
 * Projects are typically organizational units or application identifiers (e.g., from a Resource Hierarchy Service)
 * to which secrets can be associated. They form a hierarchical structure.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Project {

    /**
     * The unique identifier for the project.
     * This is the primary key.
     */
    @Id
    private String id;

    /**
     * The identifier of the Key Management Service (KMS) key associated with this project.
     * This key may be used as a default for encrypting secrets under this project, or for project-specific configurations.
     */
    private String kmsKeyId;

} 