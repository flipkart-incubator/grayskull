package com.flipkart.grayskull.authz;

import com.flipkart.grayskull.spi.GrayskullAuthorizationProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.io.Serializable;

/**
 * A custom {@link PermissionEvaluator} that integrates Grayskull's authorization logic with Spring Security's
 * method-level security. It acts as a bridge between the {@code @PreAuthorize("hasPermission(...)")} annotations
 * and the {@link GrayskullAuthorizationProvider}.
 */
@Component
@RequiredArgsConstructor
public class GrayskullPermissionEvaluator implements PermissionEvaluator {

    private final GrayskullAuthorizationProvider authorizationProvider;

    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        if ((authentication == null) || (targetDomainObject == null) || !(permission instanceof String)) {
            return false;
        }
        String projectId = targetDomainObject.toString();
        return authorizationProvider.isAuthorized(authentication, projectId, permission.toString());
    }

    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType, Object permission) {
        // Not used in this implementation
        return false;
    }
} 