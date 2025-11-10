package com.flipkart.grayskull.models;

import lombok.Getter;
import lombok.Setter;

/**
 * Configuration properties for the Grayskull client.
 * <p>
 * This class holds all the necessary configuration parameters required to connect
 * to and interact with the Grayskull.
 * </p>
 */
@Getter
@Setter
public class GrayskullProperties {
    
    /**
     * The Grayskull server endpoint URL.
     * <p>
     * This should be the DNS of the Grayskull server (e.g., "https://grayskull.example.com").
     * The client will append appropriate API paths to this host.
     * </p>
     */
    private String host;
    
    /**
     * The interval in milliseconds between long polling requests.
     * <p>
     * When using long polling to watch for secret changes, this determines how frequently
     * the client should poll the server. A lower value means more frequent updates but
     * higher network traffic.
     * </p>
     * <p>
     * Default: 30000ms (30 seconds)
     * </p>
     */
    private int refreshPollInterval = 30000;
    
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
}
