package com.flipkart.grayskull.audit.relay;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings of the audit outbox relayer job. The cron itself is {@code grayskull.audit.relayer.cron}
 * (default every minute).
 */
@Data
@ConfigurationProperties(prefix = "grayskull.audit.relayer")
public class AuditRelayerProperties {

    /** Turns the job off without a deploy. */
    private boolean enabled = true;

    private int batchSize = 100;

    /** Stop draining after this long; must stay below the lock's lockAtMostFor (PT6M). */
    private Duration maxRuntime = Duration.ofMinutes(5);

    /** How long SYNCED entries stay in the outbox. */
    private Duration purgeAfter = Duration.ofDays(30);
}
