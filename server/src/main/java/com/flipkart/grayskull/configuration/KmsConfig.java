package com.flipkart.grayskull.configuration;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class for Key Management Service (KMS) settings.
 * <p>
 * This class binds to the {@code grayskull.kms} prefix in application configuration
 * and provides KMS-related settings such as the default key ID.
 * <p>
 * Example configuration:
 * <pre>
 * grayskull:
 *   kms:
 *     defaultKeyId: key1
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "grayskull.kms")
@Getter
@Setter
public class KmsConfig {

    /**
     * The default KMS key ID to use for encryption when no project-specific
     * key is configured. This key ID must match one of the keys defined in
     * {@link CryptoConfig}.
     */
    private String defaultKeyId;

}

