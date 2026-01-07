package com.flipkart.grayskull.spimpl.validation;

import com.flipkart.grayskull.spi.MetadataValidator;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Component
@AllArgsConstructor
public final class ExternalProviderMetadataValidator implements MetadataValidator {
    public void validateMetadata(String provider, Map<String, Object> metadata) {
        if ("SELF".equals(provider)) {
            return;
        }
        if (!(metadata.get("revokeUrl") instanceof String) || !(metadata.get("rotateUrl") instanceof String)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expected mandatory keys 'revokeUrl' and 'rotateUrl' in the providerMeta");
        }
    }
}
