package com.flipkart.grayskull.configuration;

import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import com.mongodb.ReadPreference;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.connection.ConnectionPoolSettings;
import org.springframework.beans.factory.annotation.Value;

/**
 * Configuration class for MongoDB beans.
 * This class provides the necessary beans for interacting with MongoDB,
 * including the MongoClient, MongoTemplate, MongoTransactionManager.
 */
@Configuration
public class MongoBeanConfiguration {

    /**
     * Creates and configures a {@link MongoClient} bean.
     *
     * @param connectionString The MongoDB connection string.
     * @param maxConnectionLifeTime The maximum connection life time in minutes.
     * @param maxConnectionIdleTime The maximum connection idle time in minutes.
     * @param maxConnecting The maximum number of connections in the pool.
     * @return A configured {@link MongoClient} instance.
     */
    @Bean
    public MongoClient mongoClient(@Value("${spring.data.mongodb.uri}") String connectionString,
                                   @Value("${spring.data.mongodb.maxConnectionLifeTime:60}") long maxConnectionLifeTime,
                                   @Value("${spring.data.mongodb.maxConnectionIdleTime:10}") long maxConnectionIdleTime,
                                   @Value("${spring.data.mongodb.maxConnecting:100}") int maxConnecting) {
        ConnectionPoolSettings connectionPoolSettings = ConnectionPoolSettings.builder()
                .maxConnectionLifeTime(maxConnectionLifeTime, TimeUnit.MINUTES)
                .maxConnectionIdleTime(maxConnectionIdleTime, TimeUnit.MINUTES)
                .maxConnecting(maxConnecting)
                .build();

        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(connectionString))
                .readPreference(ReadPreference.secondaryPreferred())
                .writeConcern(WriteConcern.MAJORITY)
                .applyToConnectionPoolSettings(builder -> builder.applySettings(connectionPoolSettings))
                .build();

        return MongoClients.create(settings);
    }

    /**
     * Creates and configures a {@link MongoTemplate} bean.
     * The MongoTemplate is configured with secondary preferred read preference and majority write concern.
     *
     * @param dbName The name of the MongoDB database.
     * @param mongoClient The {@link MongoClient} to use.
     * @return A configured {@link MongoTemplate} instance.
     */
    @Bean
    public MongoTemplate mongoTemplate(@Value("${spring.data.mongodb.database}") String dbName, MongoClient mongoClient) {
        return new MongoTemplate(mongoClient, dbName);
    }

    /**
     * Creates a {@link MongoTransactionManager} bean.
     * This enables transaction support for MongoDB operations.
     *
     * @param dbFactory The {@link MongoDatabaseFactory} to use.
     * @return A {@link MongoTransactionManager} instance.
     */
    @Bean
    MongoTransactionManager transactionManager(MongoDatabaseFactory dbFactory) {
        return new MongoTransactionManager(dbFactory);
    }
    
}