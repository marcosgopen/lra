/*
  Copyright The Narayana Authors
  SPDX-License-Identifier: Apache-2.0
*/

package io.narayana.lra.client.api;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Configuration class for LRA Coordinator clients supporting both single and clustered modes.
 * Loads configuration from MicroProfile Config properties.
 *
 * Configuration properties:
 * <ul>
 * <li>lra.coordinator.url - Single coordinator URL</li>
 * <li>lra.coordinator.urls - Comma-separated list of coordinator URLs for clustering</li>
 * <li>lra.coordinator.service-name - Service name for Stork discovery (default: lra-coordinator)</li>
 * <li>lra.coordinator.lb-method - Load balancing method (default: round-robin)</li>
 * <li>lra.coordinator.timeout - Operation timeout in seconds (default: 30)</li>
 * <li>lra.coordinator.max-retries - Maximum retry attempts (default: 3)</li>
 * <li>lra.coordinator.api-version - LRA API version to use</li>
 * </ul>
 */
public class LRACoordinatorConfig {

    private static final String CONFIG_PREFIX = "lra.coordinator";

    private final Config config;

    // Configuration properties with defaults
    private final String singleUrl;
    private final List<String> clusterUrls;
    private final String serviceName;
    private final String loadBalancingMethod;
    private final Duration operationTimeout;
    private final int maxRetries;
    private final String apiVersion;
    private final boolean clustered;

    /**
     * Create configuration from the default MicroProfile Config instance.
     */
    public LRACoordinatorConfig() {
        this(ConfigProvider.getConfig());
    }

    /**
     * Create configuration from the specified Config instance.
     *
     * @param config The MicroProfile Config instance
     */
    public LRACoordinatorConfig(Config config) {
        this.config = config;

        // Load single coordinator URL
        this.singleUrl = config.getOptionalValue(CONFIG_PREFIX + ".url", String.class).orElse(null);

        // Load cluster URLs
        String urlsProperty = config.getOptionalValue(CONFIG_PREFIX + ".urls", String.class).orElse(null);
        this.clusterUrls = urlsProperty != null ? Arrays.asList(urlsProperty.split(",")) : List.of();

        // Determine if clustering is enabled
        this.clustered = !clusterUrls.isEmpty();

        // Load other configuration
        this.serviceName = config.getOptionalValue(CONFIG_PREFIX + ".service-name", String.class)
                .orElse("lra-coordinator");

        this.loadBalancingMethod = config.getOptionalValue(CONFIG_PREFIX + ".lb-method", String.class)
                .orElse("round-robin");

        this.operationTimeout = Duration.ofSeconds(
                config.getOptionalValue(CONFIG_PREFIX + ".timeout", Long.class).orElse(30L));

        this.maxRetries = config.getOptionalValue(CONFIG_PREFIX + ".max-retries", Integer.class)
                .orElse(3);

        this.apiVersion = config.getOptionalValue(CONFIG_PREFIX + ".api-version", String.class)
                .orElse("1.0");
    }

    /**
     * Create a configured LRA Coordinator Client based on the configuration.
     *
     * @return A configured client instance
     * @throws IllegalStateException if no coordinator configuration is found
     */
    public AutoCloseable createClient() {
        LRACoordinatorClientBuilder builder = LRACoordinatorClientBuilder.newBuilder()
                .apiVersion(apiVersion)
                .operationTimeout(operationTimeout)
                .maxRetries(maxRetries);

        if (clustered) {
            return builder
                    .coordinatorUrls(clusterUrls)
                    .serviceName(serviceName)
                    .loadBalancingMethod(loadBalancingMethod)
                    .buildClusteredClient();
        } else if (singleUrl != null) {
            return builder
                    .coordinatorUrl(singleUrl)
                    .buildSingleClient();
        } else {
            throw new IllegalStateException(
                    "No LRA coordinator configuration found. Please set either '" +
                            CONFIG_PREFIX + ".url' or '" + CONFIG_PREFIX + ".urls' property.");
        }
    }

    /**
     * Get the single coordinator URL.
     *
     * @return The single coordinator URL or null if not configured
     */
    public String getSingleUrl() {
        return singleUrl;
    }

    /**
     * Get the cluster coordinator URLs.
     *
     * @return The list of cluster coordinator URLs (may be empty)
     */
    public List<String> getClusterUrls() {
        return clusterUrls;
    }

    /**
     * Get the service name for Stork discovery.
     *
     * @return The service name
     */
    public String getServiceName() {
        return serviceName;
    }

    /**
     * Get the load balancing method.
     *
     * @return The load balancing method
     */
    public String getLoadBalancingMethod() {
        return loadBalancingMethod;
    }

    /**
     * Get the operation timeout.
     *
     * @return The operation timeout duration
     */
    public Duration getOperationTimeout() {
        return operationTimeout;
    }

    /**
     * Get the maximum retry attempts.
     *
     * @return The maximum retry attempts
     */
    public int getMaxRetries() {
        return maxRetries;
    }

    /**
     * Get the LRA API version.
     *
     * @return The API version
     */
    public String getApiVersion() {
        return apiVersion;
    }

    /**
     * Check if clustering is enabled.
     *
     * @return true if clustering is enabled
     */
    public boolean isClustered() {
        return clustered;
    }

    /**
     * Get a configuration value.
     *
     * @param key The configuration key
     * @param type The value type
     * @param <T> The value type
     * @return The configuration value if present
     */
    public <T> Optional<T> getValue(String key, Class<T> type) {
        return config.getOptionalValue(key, type);
    }

    /**
     * Get a configuration value with default.
     *
     * @param key The configuration key
     * @param defaultValue The default value
     * @param <T> The value type
     * @return The configuration value or default
     */
    public <T> T getValue(String key, T defaultValue) {
        return config.getOptionalValue(key, (Class<T>) defaultValue.getClass()).orElse(defaultValue);
    }

    /**
     * Validate the current configuration.
     *
     * @throws IllegalStateException if configuration is invalid
     */
    public void validate() {
        if (!clustered && singleUrl == null) {
            throw new IllegalStateException(
                    "No LRA coordinator configuration found. Please set either '" +
                            CONFIG_PREFIX + ".url' or '" + CONFIG_PREFIX + ".urls' property.");
        }

        if (clustered && clusterUrls.isEmpty()) {
            throw new IllegalStateException(
                    "Cluster mode enabled but no coordinator URLs provided in '" +
                            CONFIG_PREFIX + ".urls' property.");
        }

        if (maxRetries < 1) {
            throw new IllegalStateException(
                    "Max retries must be at least 1, got: " + maxRetries);
        }

        if (operationTimeout.isNegative() || operationTimeout.isZero()) {
            throw new IllegalStateException(
                    "Operation timeout must be positive, got: " + operationTimeout);
        }
    }

    @Override
    public String toString() {
        return String.format(
                "LRACoordinatorConfig{clustered=%s, singleUrl='%s', clusterUrls=%s, serviceName='%s', " +
                        "loadBalancingMethod='%s', operationTimeout=%s, maxRetries=%d, apiVersion='%s'}",
                clustered, singleUrl, clusterUrls, serviceName, loadBalancingMethod,
                operationTimeout, maxRetries, apiVersion);
    }
}