/*
  Copyright The Narayana Authors
  SPDX-License-Identifier: Apache-2.0
*/

package io.narayana.lra.client.api;

import static io.narayana.lra.LRAConstants.CURRENT_API_VERSION_STRING;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Builder class for creating LRA Coordinator Client instances with custom configuration.
 * Supports both single coordinator and clustered configurations.
 */
public class LRACoordinatorClientBuilder {

    // Single coordinator configuration
    private String coordinatorBaseUrl;

    // Cluster configuration
    private List<String> coordinatorUrls = new ArrayList<>();
    private String serviceName = "lra-coordinator";
    private String loadBalancingMethod = "round-robin";
    private boolean clustered = false;

    // Common configuration
    private String apiVersion = CURRENT_API_VERSION_STRING;
    private long connectTimeout = 30; // seconds
    private long readTimeout = 60; // seconds
    private TimeUnit timeUnit = TimeUnit.SECONDS;
    private Duration operationTimeout = Duration.ofSeconds(30);
    private int maxRetries = 3;

    private LRACoordinatorClientBuilder() {
    }

    /**
     * Create a new builder instance.
     *
     * @return A new LRACoordinatorClientBuilder
     */
    public static LRACoordinatorClientBuilder newBuilder() {
        return new LRACoordinatorClientBuilder();
    }

    /**
     * Set the coordinator base URL for single coordinator mode.
     *
     * @param coordinatorBaseUrl The base URL of the LRA Coordinator service
     * @return This builder instance
     */
    public LRACoordinatorClientBuilder coordinatorUrl(String coordinatorBaseUrl) {
        this.coordinatorBaseUrl = coordinatorBaseUrl;
        this.clustered = false;
        return this;
    }

    /**
     * Add multiple coordinator URLs for clustered mode.
     *
     * @param urls The URLs of LRA Coordinator services
     * @return This builder instance
     */
    public LRACoordinatorClientBuilder coordinatorUrls(String... urls) {
        this.coordinatorUrls.addAll(Arrays.asList(urls));
        this.clustered = true;
        return this;
    }

    /**
     * Add multiple coordinator URLs for clustered mode.
     *
     * @param urls The list of URLs of LRA Coordinator services
     * @return This builder instance
     */
    public LRACoordinatorClientBuilder coordinatorUrls(List<String> urls) {
        this.coordinatorUrls.addAll(urls);
        this.clustered = true;
        return this;
    }

    /**
     * Set the service name for clustered mode (used with Stork service discovery).
     *
     * @param serviceName The service name for Stork service discovery
     * @return This builder instance
     */
    public LRACoordinatorClientBuilder serviceName(String serviceName) {
        this.serviceName = serviceName;
        return this;
    }

    /**
     * Set the load balancing method for clustered mode.
     * Available options: round-robin, random, sticky, least-requests, least-response-time, power-of-two-choices
     *
     * @param loadBalancingMethod The load balancing method to use
     * @return This builder instance
     */
    public LRACoordinatorClientBuilder loadBalancingMethod(String loadBalancingMethod) {
        this.loadBalancingMethod = loadBalancingMethod;
        return this;
    }

    /**
     * Enable clustered mode with default configuration.
     *
     * @return This builder instance
     */
    public LRACoordinatorClientBuilder clustered() {
        this.clustered = true;
        return this;
    }

    /**
     * Enable clustered mode with specific configuration.
     *
     * @param serviceName The service name for discovery
     * @param loadBalancingMethod The load balancing method
     * @return This builder instance
     */
    public LRACoordinatorClientBuilder clustered(String serviceName, String loadBalancingMethod) {
        this.clustered = true;
        this.serviceName = serviceName;
        this.loadBalancingMethod = loadBalancingMethod;
        return this;
    }

    /**
     * Set the LRA API version.
     *
     * @param apiVersion The LRA API version to use
     * @return This builder instance
     */
    public LRACoordinatorClientBuilder apiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
        return this;
    }

    /**
     * Set the connection timeout.
     *
     * @param timeout The timeout value
     * @param unit The time unit for the timeout
     * @return This builder instance
     */
    public LRACoordinatorClientBuilder connectTimeout(long timeout, TimeUnit unit) {
        this.connectTimeout = timeout;
        this.timeUnit = unit;
        return this;
    }

    /**
     * Set the read timeout.
     *
     * @param timeout The timeout value
     * @param unit The time unit for the timeout
     * @return This builder instance
     */
    public LRACoordinatorClientBuilder readTimeout(long timeout, TimeUnit unit) {
        this.readTimeout = timeout;
        this.timeUnit = unit;
        return this;
    }

    /**
     * Set the operation timeout for clustered operations.
     *
     * @param timeout The timeout duration
     * @return This builder instance
     */
    public LRACoordinatorClientBuilder operationTimeout(Duration timeout) {
        this.operationTimeout = timeout;
        return this;
    }

    /**
     * Set the maximum number of retries for failed operations in clustered mode.
     *
     * @param maxRetries The maximum number of retries
     * @return This builder instance
     */
    public LRACoordinatorClientBuilder maxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
        return this;
    }

    /**
     * Build the appropriate LRA Coordinator Client based on configuration.
     *
     * @return A configured LRACoordinatorClient or ClusteredLRACoordinatorClient instance
     * @throws IllegalStateException if required configuration is not set
     */
    public AutoCloseable build() {
        if (clustered) {
            return buildClusteredClient();
        } else {
            return buildSingleClient();
        }
    }

    /**
     * Build a single coordinator client.
     *
     * @return A configured LRACoordinatorClient instance
     * @throws IllegalStateException if coordinatorUrl is not set
     */
    public LRACoordinatorClient buildSingleClient() {
        if (coordinatorBaseUrl == null || coordinatorBaseUrl.trim().isEmpty()) {
            throw new IllegalStateException("Coordinator base URL must be specified for single client mode");
        }

        return new LRACoordinatorClient(coordinatorBaseUrl, apiVersion);
    }

    /**
     * Build a clustered coordinator client.
     *
     * @return A configured ClusteredLRACoordinatorClient instance
     * @throws IllegalStateException if cluster configuration is invalid
     */
    public ClusteredLRACoordinatorClient buildClusteredClient() {
        if (serviceName == null || serviceName.trim().isEmpty()) {
            throw new IllegalStateException("Service name must be specified for clustered client mode");
        }

        // Create the clustered client
        ClusteredLRACoordinatorClient client = new ClusteredLRACoordinatorClient(serviceName, apiVersion);

        // Register coordinator URLs if provided
        coordinatorUrls.forEach(client::registerCoordinator);

        return client;
    }

    /**
     * Check if the builder is configured for clustered mode.
     *
     * @return true if clustered mode is enabled
     */
    public boolean isClustered() {
        return clustered;
    }

    /**
     * Get the current list of coordinator URLs (for clustered mode).
     *
     * @return The list of coordinator URLs
     */
    public List<String> getCoordinatorUrls() {
        return new ArrayList<>(coordinatorUrls);
    }

    /**
     * Get the service name (for clustered mode).
     *
     * @return The service name
     */
    public String getServiceName() {
        return serviceName;
    }

    /**
     * Get the load balancing method (for clustered mode).
     *
     * @return The load balancing method
     */
    public String getLoadBalancingMethod() {
        return loadBalancingMethod;
    }
}