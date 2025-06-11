package com.flipkart.grayskull.aspects;

import com.flipkart.grayskull.configuration.properties.ReadOnlyAppProperties;
import io.micrometer.common.lang.Nullable;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.server.NotAcceptableStatusException;

/**
 * An aspect that checks if the application is read only and disallows API calls that are not GET or annotated ReadOnly.
 */
@Aspect
@Component
public class ReadOnlyAspect {

    private final boolean readOnly;

    public ReadOnlyAspect(ReadOnlyAppProperties readOnlyAppProperties) {
        this.readOnly = readOnlyAppProperties.isEnabled();
    }

    /**
     * AOP advice that only blocks methods in RestControllers that are not GET or annotated ReadOnly.
     */
    @Around("@within(org.springframework.web.bind.annotation.RestController)" +
            " && !@annotation(org.springframework.web.bind.annotation.GetMapping)" +
            " && !@annotation(com.flipkart.grayskull.spi.annotations.ReadOnly)" +
            " && execution(* *(..))")
    @Nullable
    public Object enforceReadOnlyMode(ProceedingJoinPoint pjp) throws Throwable {
        if (!readOnly) {
            return pjp.proceed();
        } else {
            throw new NotAcceptableStatusException("The application server is read only. The specified operation is not allowed.");
        }
    }
}
