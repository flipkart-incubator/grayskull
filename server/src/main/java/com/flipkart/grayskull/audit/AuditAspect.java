package com.flipkart.grayskull.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.audit.utils.SanitizingObjectMapper;
import com.flipkart.grayskull.models.db.AuditEntry;
import com.flipkart.grayskull.models.dto.request.CreateSecretRequest;
import com.flipkart.grayskull.models.dto.response.CreateSecretResponse;
import com.flipkart.grayskull.models.dto.response.UpgradeSecretDataResponse;
import com.flipkart.grayskull.models.enums.AuditStatus;
import com.flipkart.grayskull.repositories.AuditEntryRepository;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Aspect for auditing methods annotated with {@link Auditable}.
 * <p>
 * This class defines the logic for intercepting method executions, capturing their context
 * (arguments, return values, exceptions), and persisting a detailed {@link AuditEntry}.
 * The auditing is performed within the same transaction as the intercepted method,
 * ensuring strong consistency between the business operation and the audit log.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditEntryRepository auditEntryRepository;
    private static final String DEFAULT_USER = "system";
    private static final ObjectMapper OBJECT_MAPPER = SanitizingObjectMapper.create();

    /**
     * Defines the pointcut for all methods annotated with {@link Auditable}.
     * This allows the advice methods to bind to the annotation instance.
     *
     * @param auditable the instance of the {@link Auditable} annotation on the intercepted method.
     */
    @Pointcut("@annotation(auditable)")
    public void auditableMethod(Auditable auditable) {
    }

    /**
     * Advice that runs after an audited method returns successfully.
     *
     * @param joinPoint the join point representing the intercepted method.
     * @param auditable the {@link Auditable} annotation from the method.
     * @param result    the object returned by the intercepted method.
     */
    @AfterReturning(pointcut = "auditableMethod(auditable)", returning = "result")
    public void auditSuccess(JoinPoint joinPoint, Auditable auditable, Object result) {
        audit(joinPoint, auditable, AuditStatus.SUCCESS, result, null);
    }

    /**
     * Advice that runs after an audited method throws an exception.
     *
     * @param joinPoint the join point representing the intercepted method.
     * @param auditable the {@link Auditable} annotation from the method.
     * @param exception the exception thrown by the intercepted method.
     */
    @AfterThrowing(pointcut = "auditableMethod(auditable)", throwing = "exception")
    public void auditFailure(JoinPoint joinPoint, Auditable auditable, Throwable exception) {
        audit(joinPoint, auditable, AuditStatus.FAILURE, null, exception);
    }

    /**
     * Core auditing logic. Constructs and saves an {@link AuditEntry}.
     *
     * @param joinPoint the join point representing the intercepted method.
     * @param auditable the annotation instance.
     * @param status    the outcome of the method execution (SUCCESS or FAILURE).
     * @param result    the method's return value (on success).
     * @param exception the exception thrown by the method (on failure).
     */
    private void audit(JoinPoint joinPoint, Auditable auditable, AuditStatus status, Object result, Throwable exception) {
        Map<String, Object> arguments = getMethodArguments(joinPoint);

        String projectId = (String) arguments.getOrDefault("projectId", "UNKNOWN");
        String secretName = extractSecretName(joinPoint, arguments);
        Integer secretVersion = extractSecretVersion(result);

        Map<String, String> metadata = buildMetadata(arguments, result, exception);

        AuditEntry entry = new AuditEntry(null, projectId, secretName, secretVersion,
                auditable.action().name(), status.name(), getUserId(), null, metadata);

        auditEntryRepository.save(entry);
    }

    /**
     * Retrieves the current user's ID from the Spring Security context.
     * Falls back to a default system user if the security context is not available.
     *
     * @return The ID of the authenticated user or a default system identifier.
     */
    private String getUserId() {
        return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .map(Authentication::getName)
                .orElse(DEFAULT_USER);
    }

    /**
     * Builds a metadata map containing all relevant information about the audited event.
     * This method serializes the method arguments and results into a JSON format,
     * masking any fields that are annotated with {@link com.flipkart.grayskull.models.audit.AuditMask}.
     *
     * @param arguments the arguments passed to the intercepted method.
     * @param result    the result returned by the method.
     * @param exception the exception thrown by the method.
     * @return A map of metadata for the audit entry.
     */
    private Map<String, String> buildMetadata(Map<String, Object> arguments, Object result, Throwable exception) {
        Map<String, String> metadata = new HashMap<>();
        arguments.forEach((key, value) -> {
            if (value != null) {
                try {
                    metadata.put(key, OBJECT_MAPPER.writeValueAsString(value));
                } catch (JsonProcessingException e) {
                    metadata.put(key, "Error serializing object: " + e.getMessage());
                }
            }
        });

        if (exception != null) {
            metadata.put("errorMessage", exception.getMessage());
            metadata.put("errorType", exception.getClass().getName());
        }
        if (result != null) {
            try {
                metadata.put("result", OBJECT_MAPPER.writeValueAsString(result));
            } catch (JsonProcessingException e) {
                metadata.put("result", "Error serializing object: " + e.getMessage());
            }
        }
        return metadata;
    }

    /**
     * Extracts the secret version from the method's result object.
     *
     * @param result the object returned by the intercepted method.
     * @return The secret version, or {@code null} if not applicable.
     */
    private Integer extractSecretVersion(Object result) {
        if (result instanceof CreateSecretResponse) {
            return 1;
        } else if (result instanceof UpgradeSecretDataResponse) {
            return ((UpgradeSecretDataResponse) result).getDataVersion();
        }
        return null;
    }

    /**
     * Extracts the secret name from the method's arguments.
     * Handles both direct string arguments and {@link CreateSecretRequest} objects.
     *
     * @param joinPoint the join point of the intercepted method.
     * @param arguments the extracted arguments of the method.
     * @return The secret name, or "UNKNOWN" if not found.
     */
    private String extractSecretName(JoinPoint joinPoint, Map<String, Object> arguments) {
        Object name = arguments.get("secretName");
        if (name instanceof String) {
            return (String) name;
        }

        return Arrays.stream(joinPoint.getArgs())
                .filter(CreateSecretRequest.class::isInstance)
                .map(CreateSecretRequest.class::cast)
                .map(CreateSecretRequest::getName)
                .findFirst()
                .orElse("UNKNOWN");
    }

    /**
     * Extracts the parameter names and values from the intercepted method.
     *
     * @param joinPoint The join point of the intercepted method.
     * @return A map where keys are parameter names and values are the argument objects.
     */
    private Map<String, Object> getMethodArguments(JoinPoint joinPoint) {
        Map<String, Object> argsMap = new HashMap<>();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] parameterNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < parameterNames.length; i++) {
            argsMap.put(parameterNames[i], args[i]);
        }
        return argsMap;
    }
} 