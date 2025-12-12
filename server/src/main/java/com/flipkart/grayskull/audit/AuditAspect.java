package com.flipkart.grayskull.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.flipkart.grayskull.audit.utils.RequestUtils;
import com.flipkart.grayskull.entities.AuditEntryEntity;
import com.flipkart.grayskull.models.dto.response.SecretResponse;
import com.flipkart.grayskull.models.dto.response.UpgradeSecretDataResponse;
import com.flipkart.grayskull.spi.authn.GrayskullUser;
import com.flipkart.grayskull.spi.models.AuditEntry;
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
import static com.flipkart.grayskull.audit.utils.SanitizingObjectMapper.MASK_OBJECT_MAPPER;

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
    private final RequestUtils requestUtils;

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
            Integer resourceVersion = extractResourceVersion(audit.action(), result);

            Map<String, String> metadata = buildMetadata(arguments, result);

            AuditEntryEntity entry = AuditEntryEntity.builder()
                    .projectId(projectId)
                    .resourceType(RESOURCE_TYPE_SECRET)
                    .resourceName(resourceName)
                    .resourceVersion(resourceVersion)
                    .action(audit.action().name())
                    .userId(getUserId())
                    .actorId(getActorId())
                    .ips(requestUtils.getRemoteIPs())
                    .metadata(metadata)
                    .build();

            auditEntryRepository.save(entry);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize audit metadata", e);
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
     * Retrieves the ID of the user who delegated the request if this was a delegated request.
     * Otherwise, returns null.
     * @return The ID of the user who delegated the request or null.
     */
    private String getActorId() {
        return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .filter(GrayskullUser.class::isInstance)
                .map(GrayskullUser.class::cast)
                .flatMap(GrayskullUser::getActorName)
                .orElse(null);
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
     * <p>
     * The metadata structure is:
     * <pre>
     * {
     *   "request": {
     *     "param1": value1,
     *     "param2": value2,
     *     ...
     *   },
     *   "result": { ... }
     * }
     * </pre>
     *
     * @param arguments the arguments passed to the intercepted method.
     * @param result    the result returned by the method.
     * @return A map of metadata for the audit entry.
     */
    private Map<String, String> buildMetadata(Map<String, Object> arguments, Object result) throws JsonProcessingException {
        Map<String, String> metadata = new HashMap<>();

        // Wrap all request parameters under the "request" key
        if (!arguments.isEmpty()) {
            Map<String, Object> requestParams = new HashMap<>();
            for (Map.Entry<String, Object> entry : arguments.entrySet()) {
                if (entry.getValue() != null) {
                    requestParams.put(entry.getKey(), entry.getValue());
                }
            }
            if (!requestParams.isEmpty()) {
                metadata.put(REQUEST_METADATA_KEY, MASK_OBJECT_MAPPER.writeValueAsString(requestParams));
            }
        }

        // Wrap response under the "result" key
        if (result != null) {
            metadata.put(RESULT_METADATA_KEY, MASK_OBJECT_MAPPER.writeValueAsString(result));
        }
        return metadata;
    }

    /**
     * Extracts the resource version based on the audit action.
     * Uses the action type (from the request context) as the source of truth
     * rather than checking response types.
     *
     * @param action the audit action being performed.
     * @param result the object returned by the intercepted method.
     * @return The resource version, or {@code null} if not applicable.
     */
    private Integer extractResourceVersion(AuditAction action, Object result) {
        return switch (action) {
            case CREATE_SECRET ->
                // For create operations, version is always 1
                    1;
            case UPGRADE_SECRET_DATA -> {
                // For upgrade operations, extract version from response
                if (result instanceof UpgradeSecretDataResponse secretResponse) {
                    yield secretResponse.getDataVersion();
                }
                yield null;
            }
            default -> null;
        };
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
        if (result instanceof SecretResponse secretResponse) {
            return secretResponse.getName();
        } else if (result instanceof UpgradeSecretDataResponse secretResponse) {
            return secretResponse.getName();
        }

        // Fall back to method arguments for void operations (like delete)
        Object name = arguments.get(SECRET_NAME_PARAM);
        if (name instanceof String s) {
            return s;
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