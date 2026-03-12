/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.infinispan;

import io.narayana.lra.coordinator.internal.ClusterCoordinationService;
import io.narayana.lra.logging.LRALogger;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;

/**
 * CDI configuration for Infinispan in LRA HA mode.
 *
 * Provides the EmbeddedCacheManager used by:
 * - InfinispanClusterCoordinator (JGroups-based leader election)
 * - InfinispanSlots (BackingSlots implementation for ObjectStore, via PR #2537)
 *
 * LRA state persistence flows through Narayana's standard ObjectStore path
 * (SlotStoreAdaptor → SlotStore → InfinispanSlots → Infinispan cache).
 * This class no longer creates LRA-specific caches — the SlotStore manages
 * its own cache via InfinispanStoreEnvironmentBean.
 */
@ApplicationScoped
public class InfinispanConfiguration {

    private static final String JNDI_CACHE_CONTAINER = "java:jboss/infinispan/container/lra";

    private EmbeddedCacheManager cacheManager;
    private boolean initialized = false;
    private boolean managedByContainer = false;

    /**
     * Initializes Infinispan cache manager.
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

            // Try WildFly subsystem mode first (JNDI lookup), fall back to embedded mode
            if (initializeFromJndi()) {
                managedByContainer = true;
                LRALogger.logger.info("Infinispan initialized via WildFly subsystem (JNDI)");
            } else {
                initializeEmbedded();
            }

            initialized = true;

            // Configure Narayana's ObjectStore to use InfinispanSlots (if available).
            // This routes all LRA state persistence through the replicated Infinispan cache.
            HAObjectStoreConfiguration.configure(cacheManager);
        } catch (Exception e) {
            LRALogger.logger.errorf(e, "Failed to initialize Infinispan for LRA HA mode");
            throw new RuntimeException("Failed to initialize Infinispan", e);
        }
    }

    /**
     * Attempts to obtain the cache manager from WildFly's Infinispan subsystem via JNDI.
     *
     * @return true if the JNDI lookup succeeded and cacheManager was set
     */
    private boolean initializeFromJndi() {
        try {
            InitialContext ctx = new InitialContext();
            cacheManager = (EmbeddedCacheManager) ctx.lookup(JNDI_CACHE_CONTAINER);
            LRALogger.logger.infof("Found WildFly-managed Infinispan cache container at %s", JNDI_CACHE_CONTAINER);
            return true;
        } catch (NameNotFoundException e) {
            LRALogger.logger.info("No WildFly Infinispan subsystem found, using embedded mode");
            return false;
        } catch (NamingException e) {
            LRALogger.logger.infof("JNDI lookup failed (%s), using embedded mode", e.getMessage());
            return false;
        }
    }

    /**
     * Initializes an embedded Infinispan cache manager with JGroups transport.
     * Used for standalone/Quarkus deployments where WildFly's subsystem is not available.
     *
     * Note: LRA state caches are not created here — the SlotStore/InfinispanSlots
     * manages its own cache via InfinispanStoreEnvironmentBean. This method only
     * sets up the JGroups transport for cluster coordination (leader election).
     */
    private void initializeEmbedded() {
        // Get cluster name from system property or environment variable
        String clusterName = System.getProperty("lra.coordinator.cluster.name",
                System.getenv().getOrDefault("LRA_CLUSTER_NAME", "lra-cluster"));

        // Build global configuration
        GlobalConfigurationBuilder globalConfig = new GlobalConfigurationBuilder();

        // Configure JGroups transport for clustering
        String jgroupsConfig = System.getProperty("lra.coordinator.jgroups.config");
        if (jgroupsConfig != null && !jgroupsConfig.isEmpty()) {
            globalConfig
                    .transport()
                    .defaultTransport()
                    .clusterName(clusterName)
                    .nodeName(getNodeName())
                    .addProperty("configurationFile", jgroupsConfig);
        } else {
            globalConfig
                    .transport()
                    .defaultTransport()
                    .clusterName(clusterName)
                    .nodeName(getNodeName());
        }

        // Set JGroups bind address if specified
        String bindAddr = System.getProperty("lra.coordinator.jgroups.bind_addr",
                System.getProperty("jgroups.bind_addr", "127.0.0.1"));
        System.setProperty("jgroups.bind_addr", bindAddr);

        globalConfig
                .globalState()
                .enable()
                .persistentLocation(getPersistentLocation());

        globalConfig
                .cacheContainer()
                .statistics(true);

        cacheManager = new DefaultCacheManager(globalConfig.build());

        LRALogger.logger.infof("Infinispan initialized in embedded mode for cluster '%s' with node name '%s'",
                clusterName, getNodeName());
        LRALogger.logger.infof("Infinispan cluster members: %s", cacheManager.getMembers());
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
     * Each node uses a separate directory to avoid file locking conflicts
     * when multiple coordinators run on the same host.
     *
     * @return the persistent location
     */
    private String getPersistentLocation() {
        String location = System.getProperty("lra.coordinator.infinispan.persistent.location");
        if (location == null || location.isEmpty()) {
            location = System.getProperty("java.io.tmpdir") + "/lra-infinispan-" + getNodeName();
        }
        return location;
    }

    /**
     * Stops the cache manager on shutdown.
     */
    @PreDestroy
    public void shutdown() {
        if (cacheManager != null && !managedByContainer) {
            LRALogger.logger.info("Stopping Infinispan cache manager");
            cacheManager.stop();
        }
    }
}
