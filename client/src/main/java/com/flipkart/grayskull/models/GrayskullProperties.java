package com.flipkart.grayskull.models;

import lombok.Getter;

/**
 * Configuration properties for the Grayskull client.
 * <p>
 * This class holds all the necessary configuration parameters required to connect
 * to and interact with the Grayskull.
 * </p>
 */
@Getter
public final class GrayskullProperties {
    
    /**
     * The Grayskull server endpoint URL.
     * <p>
     * This should be the DNS of the Grayskull server (e.g., "https://grayskull.example.com").
     * The client will append appropriate API paths to this host.
     * </p>
     */
    private String host;
    
    /**
     * The connection timeout in milliseconds.
     * <p>
     * This is the maximum time the client will wait when establishing a connection
     * to the Grayskull server. If the connection cannot be established within this time,
     * a timeout exception will be thrown.
     * </p>
     * <p>
     * Default: 10000ms (10 seconds)
     * </p>
     */
    private int connectionTimeout = 10000;
    
    /**
     * The read timeout in milliseconds.
     * <p>
     * This is the maximum time the client will wait for data to be received from the
     * Grayskull server after a connection has been established. If no data is received
     * within this time, a timeout exception will be thrown.
     * </p>
     * <p>
     * Default: 30000ms (30 seconds)
     * </p>
     */
    private int readTimeout = 30000;
    
    /**
     * The maximum number of concurrent connections to maintain.
     * <p>
     * This controls the connection pool size for the HTTP client. More connections
     * allow for more concurrent requests but consume more resources.
     * </p>
     * <p>
     * Default: 10
     * </p>
     */
    private int maxConnections = 10;
    
    /**
     * The maximum number of retry attempts for failed requests.
     * <p>
     * When a request to the Grayskull server fails due to transient errors
     * (e.g., network issues, server temporarily unavailable), the client will
     * retry the request up to this many times before giving up.
     * </p>
     * <p>
     * Default: 3
     * </p>
     */
    private int maxRetries = 3;

    /**
     * Sets the Grayskull server endpoint URL.
     *
     * @param host the server URL (must not be null or empty)
     * @throws IllegalArgumentException if host is null or empty
     */
    public void setHost(String host) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("Host cannot be null or empty");
        }
        // Remove trailing slash if present
        this.host = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
    }

    /**
     * Sets the connection timeout in milliseconds.
     *
     * @param connectionTimeout the connection timeout in milliseconds (must be positive)
     * @throws IllegalArgumentException if connectionTimeout is not positive
     */
    public void setConnectionTimeout(int connectionTimeout) {
        if (connectionTimeout <= 0) {
            throw new IllegalArgumentException("Connection timeout must be positive, got: " + connectionTimeout);
        }
        this.connectionTimeout = connectionTimeout;
    }

    /**
     * Sets the read timeout in milliseconds.
     *
     * @param readTimeout the read timeout in milliseconds (must be positive)
     * @throws IllegalArgumentException if readTimeout is not positive
     */
    public void setReadTimeout(int readTimeout) {
        if (readTimeout <= 0) {
            throw new IllegalArgumentException("Read timeout must be positive, got: " + readTimeout);
        }
        this.readTimeout = readTimeout;
    }

    /**
     * Sets the maximum number of concurrent connections.
     *
     * @param maxConnections the maximum number of connections (must be positive)
     * @throws IllegalArgumentException if maxConnections is not positive
     */
    public void setMaxConnections(int maxConnections) {
        if (maxConnections <= 0) {
            throw new IllegalArgumentException("Max connections must be positive, got: " + maxConnections);
        }
        this.maxConnections = maxConnections;
    }

    /**
     * Sets the maximum number of retry attempts.
     *
     * @param maxRetries the maximum number of retry attempts (must be between 1 and 10)
     * @throws IllegalArgumentException if maxRetries is less than 1 or greater than 10
     */
    public void setMaxRetries(int maxRetries) {
        if (maxRetries < 1) {
            throw new IllegalArgumentException("Max retries cannot be less than 1, got: " + maxRetries);
        }
        if (maxRetries > 10) {
            throw new IllegalArgumentException("Max retries cannot be greater than 10, got: " + maxRetries);
        }
        this.maxRetries = maxRetries;
    }
}
