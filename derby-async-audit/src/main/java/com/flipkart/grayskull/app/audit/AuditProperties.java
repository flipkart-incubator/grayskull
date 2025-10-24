package com.flipkart.grayskull.app.audit;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "audit")
@AllArgsConstructor
@NoArgsConstructor
@Validated
public class AuditProperties {
    /**
     * Folder for Apache Derby where audit events are stored
     */
    @NotEmpty
    private String derbyDirectory;

    /**
     * Unique name for this instance of the service. needs to be unique because this name is persisted to DB
     */
    @NotEmpty
    private String nodeName;

    /**
     * Batch size for fetching entries from Apache Derby to store into the DB
     */
    @Min(1)
    private int batchSize;

    /**
     * The time interval in which the cron job runs which persists the events to the DB. can use suffixes ns, us, ms, s, m, h, d
     * see {@link org.springframework.scheduling.annotation.Scheduled#fixedRateString() Scheduled.fixedRateString()}.
     */
    @NotEmpty
    private String batchTimeInterval;

    /**
     * If the checkpoint is older than this duration, then the data present in the Derby db is considered and deleted.
     * If the duration is set to zero, then staleness check is disabled
     */
    private Duration stalenessDuration = Duration.ZERO;

}
