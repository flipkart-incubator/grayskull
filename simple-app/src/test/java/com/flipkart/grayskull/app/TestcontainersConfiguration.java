package com.flipkart.grayskull.app;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.MySQLContainer;

/**
 * MongoDB (secrets and the audit outbox; a replica set, so transactions work) and MySQL (audit store) for tests.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    private final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:latest");
    private final MySQLContainer<?> mySQLContainer = new MySQLContainer<>("mysql:8.0");

    @PreDestroy
    void stopContainers() {
        mongoDBContainer.stop();
        mySQLContainer.stop();
    }

    @Bean
    DynamicPropertyRegistrar containerProperties() {
        mongoDBContainer.start();
        mySQLContainer.start();
        return registry -> {
            registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
            registry.add("spring.datasource.url", mySQLContainer::getJdbcUrl);
            registry.add("spring.datasource.username", mySQLContainer::getUsername);
            registry.add("spring.datasource.password", mySQLContainer::getPassword);
        };
    }
}
