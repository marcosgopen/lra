/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.config;

import io.smallrye.config.ConfigMapping;
import java.util.Optional;

/**
 * Main configuration interface for the LRA client.
 * Aggregates all configuration aspects including SSL, JWT, and load balancing.
 */
@ConfigMapping(prefix = "lra.coordinator")
public interface LRAClientConfig {

    /**
     * URL(s) of the LRA coordinator(s).
     * Can be a single URL or comma-separated list for clustering.
     * Defaults to "http://localhost:8080/lra-coordinator".
     */
    Optional<String> url();

    /**
     * SSL configuration for secure coordinator communication.
     */
    SslConfiguration ssl();

    /**
     * JWT authentication configuration.
     */
    JwtConfiguration jwt();

    /**
     * Load balancer configuration for coordinator clusters.
     */
    LoadBalancerConfiguration loadBalancer();

    /**
     * Get the coordinator URL with default fallback.
     */
    default String getCoordinatorUrl() {
        return url().orElse("http://localhost:8080/lra-coordinator");
    }

    /**
     * Get the coordinator URLs as an array.
     * Handles both single URLs and comma-separated lists.
     */
    default String[] getCoordinatorUrls() {
        return getCoordinatorUrl().split(",");
    }

    /**
     * Get the number of configured coordinators.
     */
    default int getCoordinatorCount() {
        return getCoordinatorUrls().length;
    }

    /**
     * Check if clustering is configured (multiple coordinators).
     */
    default boolean isClustered() {
        return getCoordinatorCount() > 1;
    }
}