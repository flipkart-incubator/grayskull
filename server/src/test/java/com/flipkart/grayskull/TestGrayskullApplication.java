package com.flipkart.grayskull;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * A dedicated Spring Boot application class for running integration tests within the 'server' module.
 * <p>
 * This class is necessary to allow Spring's test context to bootstrap a full application environment
 * for integration tests (annotated with {@code @SpringBootTest}). It serves as the main entry point
 * for tests, scanning for all necessary components, configurations, and repositories within the
 * {@code com.flipkart.grayskull} package.
 * <p>
 * Using this test-specific application class avoids a circular dependency that would occur if the 'server'
 * module's tests tried to depend on the main {@code GrayskullApplication} located in the 'simple-app' module.
 */
@SpringBootApplication(scanBasePackages = "com.flipkart.grayskull")
@EnableMongoRepositories(basePackages = "com.flipkart.grayskull.repositories")
public class TestGrayskullApplication {
    public void main(String[] args) {
    }
} 