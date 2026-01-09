package com.flipkart.grayskull.spimpl.validation;

import com.flipkart.grayskull.exception.BadRequestException;
import com.flipkart.grayskull.spi.MetadataValidator;
import lombok.AllArgsConstructor;
import org.apache.commons.validator.routines.UrlValidator;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.flipkart.grayskull.service.utils.SecretProviderConstants.*;

@Component
@AllArgsConstructor
public final class ExternalProviderMetadataValidator implements MetadataValidator {

    private final UrlValidator urlValidator = new UrlValidator(new String[]{"http", "https"});

    public void validateMetadata(String provider, Map<String, Object> metadata) {
        if (PROVIDER_SELF.equals(provider)) {
            return;
        }
        validateParam(metadata, REVOKE_URL_KEY);
        validateParam(metadata, ROTATE_URL_KEY);
    }

    private void validateParam(Map<String, Object> metadata, String key) {
        if (!(metadata.get(key) instanceof  String url)) {
            throw new BadRequestException("expected mandatory key '" + key + "' in the providerMeta");
        }
        if (!urlValidator.isValid(url)) {
            throw new BadRequestException("invalid url: " + url + " for the providerMeta key: " + key);
        }
    }
}
