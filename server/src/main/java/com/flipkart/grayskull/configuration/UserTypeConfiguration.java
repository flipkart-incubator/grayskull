package com.flipkart.grayskull.configuration;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for user type identification in audit entries.
 * Maps user type strings to userId prefix patterns for filtering audit queries.
 * 
 * <p>Required configuration in application.yml:</p>
 * <pre>
 * grayskull:
 *   audit:
 *     user-types:
 *       service-user-prefix: "authn:"
 *       human-user-prefix: "ldap:"
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "grayskull.audit.user-types")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserTypeConfiguration {
    
    /**
     * Prefix pattern to identify service/system users.
     */
    private String serviceUserPrefix;
    
    /**
     * Prefix pattern to identify human users.
     */
    private String humanUserPrefix;
}
