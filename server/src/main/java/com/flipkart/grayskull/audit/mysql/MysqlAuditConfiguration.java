package com.flipkart.grayskull.audit.mysql;

import com.flipkart.grayskull.audit.mysql.entity.AuditEntryRow;
import com.flipkart.grayskull.audit.mysql.repository.AuditEntryRowRepository;
import jakarta.persistence.EntityManagerFactory;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.mongo.MongoLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.flywaydb.core.api.Location;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Wires the MySQL audit store next to MongoDB:
 * <ul>
 *     <li>its own entity manager and {@value #TRANSACTION_MANAGER} (MongoDB's transaction manager stays primary),</li>
 *     <li>Spring Data JPA repositories for the audit tables,</li>
 *     <li>the {@code classpath:db/audit} Flyway migrations,</li>
 *     <li>ShedLock with a MongoDB lock provider, so scheduled audit jobs run on one pod at a time.</li>
 * </ul>
 * The {@link DataSource} itself comes from Spring Boot ({@code spring.datasource.*}, HikariCP).
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaRepositories(basePackageClasses = AuditEntryRowRepository.class,
        entityManagerFactoryRef = MysqlAuditConfiguration.ENTITY_MANAGER_FACTORY,
        transactionManagerRef = MysqlAuditConfiguration.TRANSACTION_MANAGER)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class MysqlAuditConfiguration {

    public static final String PERSISTENCE_UNIT = "audit";
    public static final String ENTITY_MANAGER_FACTORY = "auditEntityManagerFactory";
    public static final String TRANSACTION_MANAGER = "auditTransactionManager";
    public static final String TRANSACTION_TEMPLATE = "auditTransactionTemplate";
    public static final String MIGRATIONS_LOCATION = "classpath:db/audit";

    @Bean(ENTITY_MANAGER_FACTORY)
    LocalContainerEntityManagerFactoryBean auditEntityManagerFactory(EntityManagerFactoryBuilder builder, DataSource dataSource,
                                                                      MysqlAuditProperties properties) {
        return builder.dataSource(dataSource)
                .packages(AuditEntryRow.class)
                .persistenceUnit(PERSISTENCE_UNIT)
                .properties(Map.of(
                        // the schema is owned by Flyway
                        "hibernate.hbm2ddl.auto", "none",
                        "hibernate.jdbc.batch_size", properties.getInsertBatchSize(),
                        "hibernate.order_inserts", true,
                        "hibernate.jdbc.time_zone", "UTC"))
                .build();
    }

    @Bean(TRANSACTION_MANAGER)
    JpaTransactionManager auditTransactionManager(@Qualifier(ENTITY_MANAGER_FACTORY) EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    @Bean(TRANSACTION_TEMPLATE)
    TransactionTemplate auditTransactionTemplate(@Qualifier(TRANSACTION_MANAGER) PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    /** Adds the audit migrations to whatever locations the application already uses. */
    @Bean
    FlywayConfigurationCustomizer auditMigrationsCustomizer() {
        return configuration -> {
            List<Location> locations = new ArrayList<>(Arrays.asList(configuration.getLocations()));
            Location auditLocation = new Location(MIGRATIONS_LOCATION);
            if (!locations.contains(auditLocation)) {
                locations.add(auditLocation);
            }
            configuration.locations(locations.toArray(Location[]::new));
        };
    }

    /** Lock documents live in the {@code shedLock} collection of the application's MongoDB database. */
    @Bean
    LockProvider auditLockProvider(MongoDatabaseFactory mongoDatabaseFactory) {
        return new MongoLockProvider(mongoDatabaseFactory.getMongoDatabase());
    }
}
