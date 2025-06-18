package com.flipkart.grayskull.configuration;

import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfiguration {

    @Bean
    public OpenApiCustomizer infoCustomizer() {
        Info info = new Info().title("Grayskull API")
                .description("Grayskull API documentation")
                .contact(new Contact().name("Flipkart Security Team"))
                .license(new License().name("Apache 2.0"))
                .version("1.0.0");
        return openApi -> openApi.info(info);
    }
}
