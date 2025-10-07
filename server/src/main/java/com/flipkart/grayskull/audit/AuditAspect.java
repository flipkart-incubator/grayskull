package com.flipkart.grayskull.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.audit.utils.SanitizingObjectMapper;
import com.flipkart.grayskull.models.db.AuditEntry;
import com.flipkart.grayskull.models.dto.request.CreateSecretRequest;
import com.flipkart.grayskull.models.dto.response.CreateSecretResponse;
import com.flipkart.grayskull.models.dto.response.UpgradeSecretDataResponse;
import com.flipkart.grayskull.spi.repositories.AuditEntryRepository;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.flipkart.grayskull.audit.AuditConstants.*;

/**
 * Aspect for auditing methods annotated with {@link Auditable}.
 * <p>
 * This class defines the logic for intercepting method executions, capturing their context
 * (arguments, return values, exceptions), and persisting a detailed {@link AuditEntry}.
 * The auditing is performed within the same transaction as the intercepted method,
 * ensuring strong consistency between the business operation and the audit log.
 * 
 * Only successful operations are audited - failures are not tracked.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditEntryRepository auditEntryRepository;
    private static final ObjectMapper OBJECT_MAPPER = SanitizingObjectMapper.create();

    /**
     * Advice that runs after an audited method returns successfully.
     * Only successful operations are audited.
     *
     * @param joinPoint the join point representing the intercepted method.
     * @param result    the object returned by the intercepted method.
     */
    @AfterReturning(pointcut = "@annotation(com.flipkart.grayskull.audit.Auditable)", returning = "result")
    public void auditSuccess(JoinPoint joinPoint, Object result) {
        Auditable auditable = ((MethodSignature) joinPoint.getSignature()).getMethod().getAnnotation(Auditable.class);
        audit(joinPoint, auditable, result);
    }

    /**
     * Core auditing logic for successful operations only.
     *
     * @param joinPoint the join point representing the intercepted method.
     * @param auditable the annotation instance.
     * @param result    the method's return value.
     */
    private void audit(JoinPoint joinPoint, Auditable auditable, Object result) {
        Map<String, Object> arguments = getMethodArguments(joinPoint);

        String projectId = (String) arguments.getOrDefault(PROJECT_ID_PARAM, UNKNOWN_VALUE);
        String resourceName = extractResourceName(joinPoint, arguments);
        Integer resourceVersion = extractResourceVersion(result);

        Map<String, String> metadata = buildMetadata(arguments, result);

        AuditEntry entry = new AuditEntry(null, projectId, RESOURCE_TYPE_SECRET, resourceName, 
                resourceVersion, auditable.action().name(), getUserId(), null, metadata);

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
     * masking any fields that are annotated with
     * {@link com.flipkart.grayskull.models.audit.AuditMask}.
     *
     * @param arguments the arguments passed to the intercepted method.
     * @param result    the result returned by the method.
     * @return A map of metadata for the audit entry.
     */
    private Map<String, String> buildMetadata(Map<String, Object> arguments, Object result) {
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

        if (result != null) {
            try {
                metadata.put(RESULT_METADATA_KEY, OBJECT_MAPPER.writeValueAsString(result));
            } catch (JsonProcessingException e) {
                metadata.put(RESULT_METADATA_KEY, "Error serializing object: " + e.getMessage());
            }
        }
        return metadata;
    }

    /**
     * Extracts the resource version from the method's result object.
     *
     * @param result the object returned by the intercepted method.
     * @return The resource version, or {@code null} if not applicable.
     */
    private Integer extractResourceVersion(Object result) {
        if (result instanceof CreateSecretResponse) {
            return 1;
        } else if (result instanceof UpgradeSecretDataResponse) {
            return ((UpgradeSecretDataResponse) result).getDataVersion();
        }
        return null;
    }

    /**
     * Extracts the resource name from the method's arguments.
     * For secret operations, this extracts the secret name from either direct string arguments 
     * or {@link CreateSecretRequest} objects.
     *
     * @param joinPoint the join point of the intercepted method.
     * @param arguments the extracted arguments of the method.
     * @return The resource name, or "UNKNOWN" if not found.
     */
    private String extractResourceName(JoinPoint joinPoint, Map<String, Object> arguments) {
        Object name = arguments.get(SECRET_NAME_PARAM);
        if (name instanceof String) {
            return (String) name;
        }

        return Arrays.stream(joinPoint.getArgs())
                .filter(CreateSecretRequest.class::isInstance)
                .map(CreateSecretRequest.class::cast)
                .map(CreateSecretRequest::getName)
                .findFirst()
                .orElse(UNKNOWN_VALUE);
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