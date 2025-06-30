package com.flipkart.grayskull.configuration;

import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "grayskull.crypto")
@Getter
@Setter
public class CryptoConfig {
    private Map<String, String> keys;
} 