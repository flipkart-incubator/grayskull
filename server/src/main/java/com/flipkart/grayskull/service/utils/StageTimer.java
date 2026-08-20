package com.flipkart.grayskull.service.utils;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.function.Supplier;

/**
 * Times a single named stage of the getSecret* request pipeline (DB lookup, response
 * mapping, audit enqueue, ...) so that per-stage latency can be attributed against the
 * total request latency. Stages that already have a clean cross-bean call boundary are
 * timed via {@code @Timed} instead (see GrayskullSecurity, SecretEncryptionUtil,
 * ChaChaEncryptionService); this helper covers same-class steps where an AOP proxy
 * wouldn't intercept the call.
 */
public final class StageTimer {

    private static final String METRIC_NAME = "grayskull.secret.stage.duration";

    private StageTimer() {
    }

    public static <T> T time(MeterRegistry registry, String operation, String stage, Supplier<T> block) {
        Timer.Sample sample = Timer.start(registry);
        try {
            return block.get();
        } finally {
            sample.stop(timer(registry, operation, stage));
        }
    }

    public static void time(MeterRegistry registry, String operation, String stage, Runnable block) {
        Timer.Sample sample = Timer.start(registry);
        try {
            block.run();
        } finally {
            sample.stop(timer(registry, operation, stage));
        }
    }

    private static Timer timer(MeterRegistry registry, String operation, String stage) {
        return Timer.builder(METRIC_NAME)
                .tag("operation", operation)
                .tag("stage", stage)
                .register(registry);
    }
}
