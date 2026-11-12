package com.flipkart.grayskull;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.MySQLContainer;

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
@SpringBootApplication
public class TestGrayskullApplication {
    public void main(String[] args) {
    }

    private final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:latest");
    private final MySQLContainer<?> mySQLContainer = new MySQLContainer<>("mysql:8.0");

    @PreDestroy
    public void onContextStoppedEvent() {
        mongoDBContainer.stop();
        mySQLContainer.stop();
    }

    @Bean
    public DynamicPropertyRegistrar dynamicPropertyRegistrar() {
        mongoDBContainer.start();
        mySQLContainer.start();
        return registry -> {
            registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
            registry.add("spring.data.mongodb.database", () -> "test");
            registry.add("spring.datasource.url", () -> mySQLContainer.getJdbcUrl() + "?rewriteBatchedStatements=true");
            registry.add("spring.datasource.username", mySQLContainer::getUsername);
            registry.add("spring.datasource.password", mySQLContainer::getPassword);
            registry.add("grayskull.crypto.keys.test-key", () -> "wVXG0jhpwG0DwTMt3sQK57hukC1Uhl/yUuvH9GOP3B4=");
        };
    }
}
