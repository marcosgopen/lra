/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.internal.infinispan;

import io.narayana.lra.coordinator.domain.model.LRAState;
import io.narayana.lra.coordinator.internal.ClusterCoordinationService;
import io.narayana.lra.coordinator.internal.LockManager;
import io.narayana.lra.logging.LRALogger;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import java.net.URI;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;

/**
 * CDI configuration for Infinispan caches used in LRA HA mode.
 *
 * Creates and manages the following caches:
 * - lra-active: Active LRAs (replicated)
 * - lra-recovering: Recovering LRAs (replicated)
 * - lra-failed: Failed LRAs (replicated)
 */
@ApplicationScoped
public class InfinispanConfiguration {

    public static final String ACTIVE_LRA_CACHE_NAME = "lra-active";
    public static final String RECOVERING_LRA_CACHE_NAME = "lra-recovering";
    public static final String FAILED_LRA_CACHE_NAME = "lra-failed";

    private EmbeddedCacheManager cacheManager;
    private boolean initialized = false;

    /**
     * Initializes Infinispan cache manager and caches.
     * Called automatically by CDI on startup.
     */
    public void initialize() {
        if (initialized) {
            return;
        }

        try {
            // Check if HA mode is enabled
            String haEnabled = System.getProperty("lra.coordinator.ha.enabled", "false");
            if (!"true".equalsIgnoreCase(haEnabled)) {
                LRALogger.logger.info("LRA HA mode is disabled, Infinispan will not be initialized");
                return;
            }

            LRALogger.logger.info("Initializing Infinispan for LRA HA mode");

            // Get cluster name from system property or environment variable
            String clusterName = System.getProperty("lra.coordinator.cluster.name",
                    System.getenv().getOrDefault("LRA_CLUSTER_NAME", "lra-cluster"));

            // Build global configuration
            GlobalConfigurationBuilder globalConfig = new GlobalConfigurationBuilder();
            globalConfig
                    .transport()
                    .defaultTransport()
                    .clusterName(clusterName)
                    .nodeName(getNodeName());

            globalConfig
                    .globalState()
                    .enable()
                    .persistentLocation(getPersistentLocation());

            globalConfig
                    .cacheContainer()
                    .statistics(true);

            cacheManager = new DefaultCacheManager(globalConfig.build());

            // Get cache mode from configuration (default: REPL_SYNC)
            CacheMode cacheMode = getCacheMode();

            // Define cache configurations with partition handling
            ConfigurationBuilder activeCacheConfig = new ConfigurationBuilder();
            activeCacheConfig
                    .clustering()
                    .cacheMode(cacheMode)
                    .partitionHandling()
                    .whenSplit(org.infinispan.partitionhandling.PartitionHandling.DENY_READ_WRITES)
                    .mergePolicy(org.infinispan.conflict.MergePolicy.PREFERRED_ALWAYS)
                    .expiration()
                    .lifespan(-1) // No expiration
                    .memory()
                    .maxCount(10000);

            ConfigurationBuilder recoveringCacheConfig = new ConfigurationBuilder();
            recoveringCacheConfig
                    .clustering()
                    .cacheMode(cacheMode)
                    .partitionHandling()
                    .whenSplit(org.infinispan.partitionhandling.PartitionHandling.DENY_READ_WRITES)
                    .mergePolicy(org.infinispan.conflict.MergePolicy.PREFERRED_ALWAYS)
                    .expiration()
                    .lifespan(-1) // No expiration
                    .memory()
                    .maxCount(10000);

            ConfigurationBuilder failedCacheConfig = new ConfigurationBuilder();
            failedCacheConfig
                    .clustering()
                    .cacheMode(cacheMode)
                    .partitionHandling()
                    .whenSplit(org.infinispan.partitionhandling.PartitionHandling.DENY_READ_WRITES)
                    .mergePolicy(org.infinispan.conflict.MergePolicy.PREFERRED_ALWAYS)
                    .expiration()
                    .lifespan(-1) // No expiration
                    .memory()
                    .maxCount(1000);

            // Create caches
            cacheManager.defineConfiguration(ACTIVE_LRA_CACHE_NAME, activeCacheConfig.build());
            cacheManager.defineConfiguration(RECOVERING_LRA_CACHE_NAME, recoveringCacheConfig.build());
            cacheManager.defineConfiguration(FAILED_LRA_CACHE_NAME, failedCacheConfig.build());

            initialized = true;
            LRALogger.logger.infof("Infinispan initialized for cluster '%s' with node name '%s'",
                    clusterName, getNodeName());
            LRALogger.logger.info("Partition handling enabled: DENY_READ_WRITES strategy - " +
                    "minority partitions will deny all operations to prevent split-brain");

        } catch (Exception e) {
            LRALogger.logger.errorf(e, "Failed to initialize Infinispan for LRA HA mode");
            throw new RuntimeException("Failed to initialize Infinispan", e);
        }
    }

    /**
     * Produces the cache manager bean.
     *
     * @return the cache manager
     */
    @Produces
    @ApplicationScoped
    @Named("lraCacheManager")
    public EmbeddedCacheManager cacheManager() {
        if (!initialized) {
            initialize();
        }
        return cacheManager;
    }

    /**
     * Produces the active LRA cache bean.
     *
     * @return the active LRA cache
     */
    @Produces
    @ApplicationScoped
    @Named("activeLRACache")
    public Cache<URI, LRAState> activeLRACache() {
        if (!initialized) {
            initialize();
        }
        return cacheManager != null ? cacheManager.getCache(ACTIVE_LRA_CACHE_NAME) : null;
    }

    /**
     * Produces the recovering LRA cache bean.
     *
     * @return the recovering LRA cache
     */
    @Produces
    @ApplicationScoped
    @Named("recoveringLRACache")
    public Cache<URI, LRAState> recoveringLRACache() {
        if (!initialized) {
            initialize();
        }
        return cacheManager != null ? cacheManager.getCache(RECOVERING_LRA_CACHE_NAME) : null;
    }

    /**
     * Produces the failed LRA cache bean.
     *
     * @return the failed LRA cache
     */
    @Produces
    @ApplicationScoped
    @Named("failedLRACache")
    public Cache<URI, LRAState> failedLRACache() {
        if (!initialized) {
            initialize();
        }
        return cacheManager != null ? cacheManager.getCache(FAILED_LRA_CACHE_NAME) : null;
    }

    /**
     * Produces the cluster coordinator bean.
     *
     * @return the cluster coordinator
     */
    @Produces
    @ApplicationScoped
    public ClusterCoordinationService clusterCoordinator() {
        if (!initialized) {
            initialize();
        }
        if (cacheManager == null) {
            return null;
        }
        InfinispanClusterCoordinator coordinator = new InfinispanClusterCoordinator();
        coordinator.initialize(cacheManager);
        return coordinator;
    }

    /**
     * Produces the lock manager bean.
     *
     * @return the lock manager
     */
    @Produces
    @ApplicationScoped
    public LockManager lockManager() {
        if (!initialized) {
            initialize();
        }
        if (cacheManager == null) {
            return null;
        }
        InfinispanLockManager lockMgr = new InfinispanLockManager();
        lockMgr.initialize(cacheManager);
        return lockMgr;
    }

    /**
     * Checks if Infinispan is initialized.
     *
     * @return true if initialized
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Gets the node name for this coordinator instance.
     *
     * @return the node name
     */
    private String getNodeName() {
        String nodeName = System.getProperty("lra.coordinator.node.id");
        if (nodeName == null || nodeName.isEmpty()) {
            nodeName = System.getenv("HOSTNAME");
        }
        if (nodeName == null || nodeName.isEmpty()) {
            nodeName = "lra-coordinator-" + System.currentTimeMillis();
        }
        return nodeName;
    }

    /**
     * Gets the persistent location for Infinispan state.
     *
     * @return the persistent location
     */
    private String getPersistentLocation() {
        String location = System.getProperty("lra.coordinator.infinispan.persistent.location");
        if (location == null || location.isEmpty()) {
            location = System.getProperty("java.io.tmpdir") + "/lra-infinispan";
        }
        return location;
    }

    /**
     * Gets the cache mode from configuration.
     * Supported modes: REPL_SYNC, REPL_ASYNC, DIST_SYNC, DIST_ASYNC, LOCAL
     *
     * System property: lra.coordinator.infinispan.cache.mode
     * Default: REPL_SYNC
     *
     * @return the configured cache mode
     */
    private CacheMode getCacheMode() {
        String mode = System.getProperty("lra.coordinator.infinispan.cache.mode", "REPL_SYNC");

        try {
            CacheMode cacheMode = CacheMode.valueOf(mode.toUpperCase());
            LRALogger.logger.infof("Infinispan cache mode: %s", cacheMode);
            return cacheMode;
        } catch (IllegalArgumentException e) {
            LRALogger.logger.warnf("Invalid cache mode '%s', using default REPL_SYNC. " +
                    "Valid modes are: REPL_SYNC, REPL_ASYNC, DIST_SYNC, DIST_ASYNC, LOCAL", mode);
            return CacheMode.REPL_SYNC;
        }
    }

    /**
     * Stops the cache manager on shutdown.
     */
    @PreDestroy
    public void shutdown() {
        if (cacheManager != null) {
            LRALogger.logger.info("Stopping Infinispan cache manager");
            cacheManager.stop();
        }
    }
}
