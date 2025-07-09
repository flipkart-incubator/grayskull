package com.flipkart.grayskull.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@SpringBootApplication(scanBasePackages = "com.flipkart.grayskull")
@EnableMongoRepositories(basePackages = "com.flipkart.grayskull.repositories")
@EnableMongoAuditing
public class GrayskullApplication {

    public static void main(String[] args) {
        SpringApplication.run(GrayskullApplication.class, args);
    }

}
