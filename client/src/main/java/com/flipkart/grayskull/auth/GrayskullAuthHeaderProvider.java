package com.flipkart.grayskull.auth;

/**
 * Interface for providing authentication headers to the Grayskull client.
 * If there are multiple authentication providers, Grayskull will pick the bean with {@code @Primary} annotation.
 */
public interface GrayskullAuthHeaderProvider {
    
    /**
     * Returns the authentication header value to be included in requests.
     * 
     * <p>
     * <strong>Important:</strong> Implementations should be thread-safe as this method
     * may be called concurrently by multiple threads.
     * </p>
     * @return the authentication header value (e.g., "Basic dXNlcm5hbWU6cGFzc3dvcmQ=")
     * @throws RuntimeException if authentication token generation fails
     */
    String getAuthHeader();
}
