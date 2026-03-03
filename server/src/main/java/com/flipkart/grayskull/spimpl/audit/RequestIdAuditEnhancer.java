package com.flipkart.grayskull.spimpl.audit;

import com.flipkart.grayskull.spi.AuditMetadataEnhancer;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@AllArgsConstructor
public class RequestIdAuditEnhancer implements AuditMetadataEnhancer {

    private final HttpServletRequest request;

    @Override
    public Map<String, String> getAdditionalMetadata() {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        return Map.of("RequestId", requestId.trim());
    }
}
