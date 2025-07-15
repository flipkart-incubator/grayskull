package com.flipkart.grayskull.configuration;

import com.flipkart.grayskull.models.db.Project;
import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class to bind and validate default project settings from the application's properties.
 * <p>
 * This class maps properties under the prefix {@code grayskull.projects} to a {@link com.flipkart.grayskull.models.db.Project} object.
 * It is used to define a system-wide default project configuration, which can provide fallback values,
 * such as a default KMS key for encryption, when no project-specific configuration is present in the database.
 */
@Configuration
@ConfigurationProperties(prefix = "grayskull.projects")
@Getter
@Setter
public class DefaultProjectConfig {

    private Project defaultProject;

}
    