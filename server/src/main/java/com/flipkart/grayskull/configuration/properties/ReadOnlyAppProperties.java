package com.flipkart.grayskull.configuration.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

@Getter
@Setter
@ConfigurationProperties(prefix = "server.read-only")
@Component
@RefreshScope
public class ReadOnlyAppProperties {
    private boolean enabled;
}
