/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.client.internal;

import static io.narayana.lra.LRAConstants.COORDINATOR_PATH_NAME;

import io.narayana.lra.client.config.LoadBalancerConfiguration;
import io.narayana.lra.logging.LRALogger;
import io.smallrye.stork.Stork;
import io.smallrye.stork.api.Service;
import io.smallrye.stork.api.ServiceDefinition;
import io.smallrye.stork.api.config.ConfigWithType;
import io.smallrye.stork.servicediscovery.staticlist.StaticConfiguration;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Manages load balancing configuration and Stork integration for LRA coordinator clusters.
 */
public class LoadBalancingManager {

    private final LoadBalancerConfiguration config;
    private final String coordinators;
    private final int coordinatorCount;
    private Service coordinatorService;
    private boolean storkInitialized = false;

    public LoadBalancingManager(LoadBalancerConfiguration config, String coordinators) {
        this.config = Objects.requireNonNull(config, "LoadBalancer configuration cannot be null");
        this.coordinators = Objects.requireNonNull(coordinators, "Coordinators cannot be null");
        this.coordinatorCount = coordinators.split(",").length;
    }

    /**
     * Initialize load balancing if clustering is configured.
     *
     * @return true if load balancing was successfully initialized
     */
    public boolean initialize() {
        if (!isClustered()) {
            return false;
        }

        if (!config.isMethodSupported()) {
            LRALogger.i18nLogger.warn_unsupportedLoadBalancer(
                    config.getMethod(),
                    coordinators.split(",")[0]);
            return false;
        }

        try {
            ConfigWithType balancer = createLoadBalancerConfig(config.getMethod(), null);

            Stork.initialize();
            var stork = Stork.getInstance()
                    .defineIfAbsent(COORDINATOR_PATH_NAME, ServiceDefinition.of(
                            new StaticConfiguration().withAddressList(coordinators),
                            balancer));

            this.coordinatorService = stork.getService(COORDINATOR_PATH_NAME);
            this.storkInitialized = true;

            if (LRALogger.logger.isDebugEnabled()) {
                LRALogger.logger.debugf("Initialized Stork with coordinators %s and lb-method %s",
                        coordinators, config.getMethod());
            }

            return true;
        } catch (NoClassDefFoundError | IllegalArgumentException error) {
            LRALogger.i18nLogger.warn_noLoadBalancer(coordinators, error);
            return false;
        }
    }

    /**
     * Get the coordinator service for load balancing.
     *
     * @return the Stork service instance, or null if not initialized
     */
    public Service getCoordinatorService() {
        return coordinatorService;
    }

    /**
     * Check if clustering is configured (multiple coordinators).
     */
    public boolean isClustered() {
        return coordinatorCount > 1;
    }

    /**
     * Check if load balancing is active and valid.
     */
    public boolean isLoadBalancing() {
        return isClustered() && config.isMethodSupported() && storkInitialized;
    }

    /**
     * Check if failover is supported with the current configuration.
     */
    public boolean supportsFailover() {
        return config.supportsFailover();
    }

    /**
     * Get the number of coordinators in the cluster.
     */
    public int getCoordinatorCount() {
        return coordinatorCount;
    }

    /**
     * Shutdown load balancing resources.
     */
    public void shutdown() {
        if (storkInitialized) {
            Stork.shutdown();
            storkInitialized = false;
        }
    }

    /**
     * Create load balancer configuration for Stork.
     */
    private ConfigWithType createLoadBalancerConfig(String loadBalancer, Map<String, String> loadBalancerParams) {
        return loadBalancer == null ? null : new ConfigWithType() {
            @Override
            public String type() {
                return loadBalancer;
            }

            @Override
            public Map<String, String> parameters() {
                return Objects.requireNonNullElse(loadBalancerParams, Collections.emptyMap());
            }
        };
    }

    /**
     * Builder for LoadBalancingManager.
     */
    public static class Builder {
        private LoadBalancerConfiguration config;
        private String coordinators;

        public static Builder newBuilder() {
            return new Builder();
        }

        public Builder withConfig(LoadBalancerConfiguration config) {
            this.config = config;
            return this;
        }

        public Builder withCoordinators(String coordinators) {
            this.coordinators = coordinators;
            return this;
        }

        public LoadBalancingManager build() {
            Objects.requireNonNull(config, "LoadBalancer configuration is required");
            Objects.requireNonNull(coordinators, "Coordinators are required");
            return new LoadBalancingManager(config, coordinators);
        }
    }
}
