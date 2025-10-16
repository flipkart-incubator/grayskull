package com.flipkart.grayskull.configuration;

import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class for cryptographic key management.
 * <p>
 * This class binds to the {@code grayskull.crypto} prefix in application configuration
 * and provides access to encryption keys used for securing secret data.
 * <p>
 * Example configuration:
 * <pre>
 * grayskull:
 *   crypto:
 *     keys:
 *       key1: "base64-encoded-key"
 *       key2: "base64-encoded-key"
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "grayskull.crypto")
@Getter
@Setter
public class CryptoConfig {
    
    /**
     * A map of key IDs to their corresponding base64-encoded encryption keys.
     * These keys are used for encrypting and decrypting secret data.
     */
    private Map<String, String> keys;
} 