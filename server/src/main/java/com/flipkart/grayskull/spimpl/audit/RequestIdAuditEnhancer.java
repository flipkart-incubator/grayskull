package com.flipkart.grayskull.spimpl.audit;

import com.flipkart.grayskull.spi.AuditMetadataEnhancer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RequestIdAuditEnhancer implements AuditMetadataEnhancer {

    @Override
    public Map<String, String> getAdditionalMetadata(HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        return Map.of("RequestId", requestId.trim());
    }
}
