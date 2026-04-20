package com.flipkart.grayskull.audit.utils;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.flipkart.grayskull.spi.AuditMetadataEnhancer;

import java.util.*;

@RequiredArgsConstructor
@Component
public class RequestUtils {

    private final HttpServletRequest request;
    private final List<AuditMetadataEnhancer> auditMetadataEnhancers;

    public Map<String, String> getRemoteIPs() {
        Map<String, String> ips = new HashMap<>();
        ips.put("Remote-Conn-Addr", request.getRemoteAddr());
        addIfNotNull(ips, "X-Forwarded-For", request.getHeader("X-Forwarded-For"));
        addIfNotNull(ips, "X-Real-IP", request.getHeader("X-Real-IP"));
        addIfNotNull(ips, "RFC7239 Forwarded", request.getHeader("Forwarded"));
        return ips;
    }

    private static void addIfNotNull(Map<String, String> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    public Map<String, String> getAdditionalMetadata() {
        Map<String, String> metadata = new HashMap<>();
        auditMetadataEnhancers.stream()
                .map(enhancer -> enhancer.getAdditionalMetadata(request))
                .filter(Objects::nonNull)
                .forEach(metadata::putAll);
        return metadata;
    }
}
