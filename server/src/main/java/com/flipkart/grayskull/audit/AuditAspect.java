package com.flipkart.grayskull.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flipkart.grayskull.audit.utils.SanitizingObjectMapper;
import com.flipkart.grayskull.entities.AuditEntryEntity;
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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.flipkart.grayskull.audit.AuditConstants.*;

/**
 * Aspect for auditing methods annotated with {@link Audit}.
 * <p>
 * This class defines the logic for intercepting method executions, capturing
 * their context
 * (arguments, return values, exceptions), and persisting a detailed
 * {@link AuditEntry}.
 * The auditing is performed within the same transaction as the intercepted
 * method,
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
    @AfterReturning(pointcut = "@annotation(com.flipkart.grayskull.audit.Audit)", returning = "result")
    public void auditSuccess(JoinPoint joinPoint, Object result) {
        Audit audit = ((MethodSignature) joinPoint.getSignature()).getMethod().getAnnotation(Audit.class);
        audit(joinPoint, audit, result);
    }

    /**
     * Core auditing logic for successful operations only.
     * <p>
     * If audit metadata serialization fails, this method throws an exception,
     * which will cause the entire operation to fail, ensuring that operations
     * without proper audit trails are not allowed.
     *
     * @param joinPoint the join point representing the intercepted method.
     * @param audit     the annotation instance.
     * @param result    the method's return value.
     */
    private void audit(JoinPoint joinPoint, Audit audit, Object result) {
        try {
            Map<String, Object> arguments = getMethodArguments(joinPoint);

            String projectId = (String) arguments.getOrDefault(PROJECT_ID_PARAM, UNKNOWN_VALUE);
            String resourceName = extractResourceName(result, arguments);
            Integer resourceVersion = extractResourceVersion(result);

            Map<String, String> metadata = buildMetadata(arguments, result);

            AuditEntryEntity entry = AuditEntryEntity.builder()
                    .projectId(projectId)
                    .resourceType(RESOURCE_TYPE_SECRET)
                    .resourceName(resourceName)
                    .resourceVersion(resourceVersion)
                    .action(audit.action().name())
                    .userId(getUserId())
                    .metadata(metadata)
                    .build();

            auditEntryRepository.save(entry);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize audit metadata", e);
        }
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
     * Builds a metadata map containing all relevant information about the audited
     * event.
     * This method serializes the method arguments and results into a JSON format,
     * masking any fields that are annotated with
     * {@link com.flipkart.grayskull.audit.AuditMask}.
     * <p>
     * If serialization fails, the method throws an exception, causing the entire
     * operation to fail, ensuring audit integrity.
     *
     * @param arguments the arguments passed to the intercepted method.
     * @param result    the result returned by the method.
     * @return A map of metadata for the audit entry.
     * @throws JsonProcessingException if serialization of any argument or result fails.
     */
    private Map<String, String> buildMetadata(Map<String, Object> arguments, Object result) throws JsonProcessingException {
        Map<String, String> metadata = new HashMap<>();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (entry.getValue() != null) {
                metadata.put(entry.getKey(), OBJECT_MAPPER.writeValueAsString(entry.getValue()));
            }
        }

        if (result != null) {
            metadata.put(RESULT_METADATA_KEY, OBJECT_MAPPER.writeValueAsString(result));
        }
        return metadata;
    }

    /**
     * Extracts the resource version from the method's result object.
     * Relies on the response entity schema as the source of truth rather than
     * HTTP method/URL or instanceof checks.
     *
     * @param result the object returned by the intercepted method.
     * @return The resource version, or {@code null} if not applicable.
     */
    private Integer extractResourceVersion(Object result) {
        if (result instanceof CreateSecretResponse) {
            return ((CreateSecretResponse) result).getCurrentDataVersion();
        } else if (result instanceof UpgradeSecretDataResponse) {
            return ((UpgradeSecretDataResponse) result).getDataVersion();
        }
        return null;
    }

    /**
     * Extracts the resource name from the method's result object or method arguments.
     * Prefers response entity schema as the source of truth (for create/upgrade operations),
     * but falls back to method arguments for operations that return void (like delete).
     *
     * @param result the object returned by the intercepted method.
     * @param arguments the method arguments map.
     * @return The resource name, or "UNKNOWN" if not found.
     */
    private String extractResourceName(Object result, Map<String, Object> arguments) {
        // Try to extract from response entity first (response schema is the source of truth)
        if (result instanceof CreateSecretResponse) {
            return ((CreateSecretResponse) result).getName();
        } else if (result instanceof UpgradeSecretDataResponse) {
            return ((UpgradeSecretDataResponse) result).getName();
        }
        
        // Fall back to method arguments for void operations (like delete)
        Object name = arguments.get(SECRET_NAME_PARAM);
        if (name instanceof String) {
            return (String) name;
        }
        
        return UNKNOWN_VALUE;
    }

    /**
     * Extracts the parameter names and values from the intercepted method.
     *
     * @param joinPoint The join point of the intercepted method.
     * @return A map where keys are parameter names and values are the argument
     *         objects.
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