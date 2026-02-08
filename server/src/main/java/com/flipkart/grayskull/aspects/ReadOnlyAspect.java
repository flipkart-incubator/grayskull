package com.flipkart.grayskull.aspects;

import com.flipkart.grayskull.configuration.properties.ReadOnlyAppProperties;
import io.micrometer.common.lang.Nullable;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * An aspect that checks if the application is read only and disallows API calls that are not GET or annotated ReadOnly.
 */
@Aspect
@Component
public class ReadOnlyAspect {

    private final ReadOnlyAppProperties readOnlyAppProperties;

    public ReadOnlyAspect(ReadOnlyAppProperties readOnlyAppProperties) {

        this.readOnlyAppProperties = readOnlyAppProperties;

    }

    private void testdummy() {
        System.out.println("test");
    }
    
    /**
     * AOP advice that only blocks methods in RestControllers that are not GET or annotated ReadOnly.
     */
    @Around("@within(org.springframework.web.bind.annotation.RestController)" +
            " && !@annotation(org.springframework.web.bind.annotation.GetMapping)" +
            " && !@annotation(com.flipkart.grayskull.aspects.annotations.BypassReadOnly)" +
            " && execution(* *(..))")
    @Nullable
    public Object enforceReadOnlyMode(ProceedingJoinPoint pjp) throws Throwable {
        if (!readOnlyAppProperties.isEnabled()) {
            return pjp.proceed();
        } else {
            throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "The application server is read only. The specified operation is not allowed.");
        }
    }
}
