package com.flipkart.grayskull.audit.mysql;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the MySQL audit store.
 */
@Data
@ConfigurationProperties(prefix = "grayskull.audit.mysql")
public class MysqlAuditProperties {

    /** Hibernate JDBC batch size for audit inserts. */
    private int insertBatchSize = 500;

    /** How many future monthly partitions the partition job keeps ready. */
    private int partitionMonthsAhead = 3;
}
