package com.flipkart.grayskull.configuration;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for user type identification in audit entries.
 * Maps user type strings to userId prefix patterns for filtering audit queries.
 * 
 * <p>Required configuration in application.yml:</p>
 * <pre>
 * grayskull:
 *   audit:
 *     user-types:
 *       service-user-prefix: "service:"
 *       human-user-prefix: "human:"
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "grayskull.audit.user-types")
@Validated
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserTypeConfiguration {
    
    /**
     * Prefix pattern to identify service/system users.
     */
    @NotBlank(message = "Service user prefix must be configured (grayskull.audit.user-types.service-user-prefix)")
    private String serviceUserPrefix;
    
    /**
     * Prefix pattern to identify human users.
     */
    @NotBlank(message = "Human user prefix must be configured (grayskull.audit.user-types.human-user-prefix)")
    private String humanUserPrefix;
}
