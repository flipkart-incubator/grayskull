package com.flipkart.grayskull.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

import com.flipkart.grayskull.spi.repositories.ProjectRepository;
import com.mongodb.ReadPreference;
import com.mongodb.TransactionOptions;

/**
 * MongoDB configuration class that enables repository scanning, auditing, and transaction management.
 * Only loads when {@link MongoDatabaseFactory} is present on the classpath.
 * Configures repository scanning starting from {@link ProjectRepository} package.
 */
@EnableMongoRepositories(basePackageClasses = ProjectRepository.class)
@EnableMongoAuditing
@ConditionalOnClass(MongoDatabaseFactory.class)
@Configuration
public class MongoBeanConfiguration {

    /**
     * Configures MongoDB transaction management with primary read preference.
     *
     * @param dbFactory Factory for obtaining {@link com.mongodb.client.MongoDatabase} instances
     * @return Configured transaction manager for MongoDB operations
     */
    @Bean
    MongoTransactionManager transactionManager(MongoDatabaseFactory dbFactory) {
        return new MongoTransactionManager(dbFactory, TransactionOptions.builder().readPreference(ReadPreference.primary()).build());
    }
    
}