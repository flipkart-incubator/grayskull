package com.flipkart.grayskull.metrics;

import java.net.URI;


final class URLNormalizer {
    
    static String normalize(String url) {
        if (url == null || url.isEmpty()) {
            return "unknown";
        }
        
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            return (path != null && !path.isEmpty()) ? path : url;
        } catch (Exception e) {
            return url;
        }
    }
}

