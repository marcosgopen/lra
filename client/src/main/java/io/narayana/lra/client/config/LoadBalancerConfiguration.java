/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.config;

import io.smallrye.config.ConfigMapping;
import java.util.Arrays;
import java.util.Optional;

/**
 * Load balancer configuration for LRA coordinator clusters.
 */
@ConfigMapping(prefix = "lra.coordinator", namingStrategy = ConfigMapping.NamingStrategy.KEBAB_CASE)
public interface LoadBalancerConfiguration {

    /**
     * Load balancing method to use.
     * Defaults to "round-robin".
     */
    Optional<String> lbMethod();

    /**
     * Get the load balancing method.
     */
    default String getMethod() {
        return lbMethod().orElse("round-robin");
    }

    /**
     * Check if the configured load balancing method is supported.
     */
    default boolean isMethodSupported() {
        String method = getMethod();
        String[] supportedMethods = {
                "round-robin", "sticky", "random",
                "least-requests", "least-response-time", "power-of-two-choices"
        };
        return Arrays.asList(supportedMethods).contains(method);
    }

    /**
     * Check if the load balancing method supports failover.
     */
    default boolean supportsFailover() {
        return "round-robin".equals(getMethod());
    }
}