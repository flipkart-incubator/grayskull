package com.flipkart.grayskull.spimpl.crypto;

import com.flipkart.grayskull.spi.EncryptionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnMissingBean(EncryptionService.class)
public class EncryptionConfiguration {

    @Bean
    public EncryptionService encryptionService(KeyProperties keyProperties) {
        return new ChaChaEncryptionService(keyProperties);
    }

    @Bean
    @ConfigurationProperties(prefix = "grayskull.crypto")
    public KeyProperties keyProperties() {
        return new KeyProperties();
    }

}
