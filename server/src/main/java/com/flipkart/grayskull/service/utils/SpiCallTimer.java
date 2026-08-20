package com.flipkart.grayskull.service.utils;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.function.Supplier;

/**
 * Times a call across one of Grayskull's pluggable SPI boundaries (authn, authz, KMS/encryption)
 * at the call site inside the OSS glue code (GrayskullSecurity, AuthenticationFilter,
 * SecretEncryptionUtil), rather than annotating the default implementation.
 * <p>
 * Deployments can swap {@code GrayskullAuthenticationProvider}, {@code GrayskullAuthorizationProvider},
 * or {@code EncryptionService} for a custom implementation (e.g. a real KMS or an internal authz
 * service); annotating the OSS default impls (SimpleAuthenticationProvider, ChaChaEncryptionService,
 * ...) would silently stop producing metrics the moment that swap happens. Timing the interface call
 * site keeps the metric stable regardless of which implementation is wired in.
 */
public final class SpiCallTimer {

    private static final String METRIC_NAME = "grayskull.spi.call.duration";

    private SpiCallTimer() {
    }

    public static <T> T time(MeterRegistry registry, String component, Supplier<T> block) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "success";
        try {
            return block.get();
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            sample.stop(Timer.builder(METRIC_NAME)
                    .tag("component", component)
                    .tag("outcome", outcome)
                    .register(registry));
        }
    }
}
