package com.flipkart.grayskull.configuration.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@ConfigurationProperties(prefix = "server.read-only")
@Component
public class ReadOnlyAppProperties {
    private boolean enabled;
}
