/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.infinispan;

import com.arjuna.ats.arjuna.common.ObjectStoreEnvironmentBean;
import com.arjuna.common.internal.util.propertyservice.BeanPopulator;
import io.narayana.lra.logging.LRALogger;
import java.lang.reflect.Method;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.manager.EmbeddedCacheManager;

/**
 * Configures Narayana's ObjectStore to use InfinispanSlots as the BackingSlots
 * implementation when HA mode is enabled. This routes all LRA state persistence
 * through a replicated Infinispan cache via:
 *
 * <pre>
 * Arjuna deactivate()
 *   → save_state()
 *     → SlotStoreAdaptor.write_committed()
 *       → SlotStore.write()
 *         → InfinispanSlots.write()
 *           → cache.put() (replicated to all nodes)
 * </pre>
 *
 * <p>
 * <b>Dependency:</b> This requires Narayana PR #2537 (InfinispanSlots) to be
 * merged into Narayana core. Until then, the configuration will detect that the
 * required classes are not on the classpath and fall back to the default
 * filesystem-based ObjectStore with a warning message.
 * </p>
 *
 * @see <a href="https://github.com/jbosstm/narayana/pull/2537">Narayana PR #2537</a>
 */
public class HAObjectStoreConfiguration {

    // Class names from Narayana core (ArjunaCore/arjuna module)
    private static final String SLOT_STORE_ADAPTOR = "com.arjuna.ats.internal.arjuna.objectstore.slot.SlotStoreAdaptor";
    private static final String INFINISPAN_SLOTS = "com.arjuna.ats.internal.arjuna.objectstore.slot.infinispan.InfinispanSlots";
    private static final String SLOT_STORE_ENV_BEAN = "com.arjuna.ats.internal.arjuna.objectstore.slot.SlotStoreEnvironmentBean";
    private static final String INFINISPAN_STORE_ENV_BEAN = "com.arjuna.ats.internal.arjuna.objectstore.slot.infinispan.InfinispanStoreEnvironmentBean";

    // ClusterMemberId key generator — produces per-node cache keys with format:
    // {groupId}:nodeId:uid:slotIndex
    // This enables multi-node HA: each node writes to its own keys, but
    // cache.keySet() returns all keys from all nodes (replicated cache).
    // On recovery, a fresh SlotStore calls load(cache.keySet()) to build
    // a complete index across all nodes.
    private static final String CLUSTER_MEMBER_ID = "com.arjuna.ats.internal.arjuna.objectstore.slot.infinispan.ClusterMemberId";

    static final String LRA_OBJECTSTORE_CACHE_NAME = "lra-objectstore";

    private static final int DEFAULT_NUMBER_OF_SLOTS = 10000;

    private HAObjectStoreConfiguration() {
    }

    /**
     * Configures the Narayana ObjectStore to use InfinispanSlots if available.
     *
     * <p>
     * This method:
     * </p>
     * <ol>
     * <li>Checks that InfinispanSlots is on the classpath (from Narayana PR #2537)</li>
     * <li>Creates a replicated Infinispan cache for LRA state storage</li>
     * <li>Configures SlotStoreAdaptor as the ObjectStore implementation</li>
     * <li>Wires the Infinispan cache into InfinispanStoreEnvironmentBean</li>
     * </ol>
     *
     * <p>
     * If InfinispanSlots is not available, logs a warning and leaves the
     * ObjectStore at its default (filesystem). The coordinator still works
     * but state is not replicated across the cluster.
     * </p>
     *
     * @param cacheManager the Infinispan EmbeddedCacheManager (must be initialized)
     * @return true if InfinispanSlots was successfully configured, false if falling back to default
     */
    public static boolean configure(EmbeddedCacheManager cacheManager) {
        if (cacheManager == null) {
            LRALogger.logger.warn("Cannot configure HA ObjectStore: cache manager is null");
            return false;
        }

        // Check if InfinispanSlots is available on the classpath
        if (!isInfinispanSlotsAvailable()) {
            LRALogger.logger.warn(
                    "InfinispanSlots (Narayana PR #2537) is not available on the classpath. "
                            + "The LRA coordinator will use the default filesystem-based ObjectStore. "
                            + "LRA state will NOT be replicated across the cluster. "
                            + "To enable HA state replication, upgrade to a Narayana version that includes "
                            + "the InfinispanSlots BackingSlots implementation.");
            return false;
        }

        try {
            // Create the replicated cache for LRA ObjectStore data
            Cache<byte[], byte[]> cache = getOrCreateCache(cacheManager);

            // Configure Narayana's ObjectStore to use SlotStoreAdaptor
            configureObjectStoreBean();

            // Configure SlotStore to use InfinispanSlots with our cache
            configureSlotStoreBean(cache);

            LRALogger.logger.info("HA ObjectStore configured: SlotStoreAdaptor → InfinispanSlots → "
                    + LRA_OBJECTSTORE_CACHE_NAME + " cache (replicated)");
            return true;

        } catch (Exception e) {
            LRALogger.logger.errorf(e, "Failed to configure HA ObjectStore. "
                    + "Falling back to default filesystem-based ObjectStore. "
                    + "LRA state will NOT be replicated across the cluster.");
            return false;
        }
    }

    /**
     * Checks if the InfinispanSlots class is available on the classpath.
     */
    private static boolean isInfinispanSlotsAvailable() {
        try {
            Class.forName(SLOT_STORE_ADAPTOR);
            Class.forName(INFINISPAN_SLOTS);
            Class.forName(SLOT_STORE_ENV_BEAN);
            Class.forName(INFINISPAN_STORE_ENV_BEAN);
            Class.forName(CLUSTER_MEMBER_ID);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Gets or creates the replicated Infinispan cache used by InfinispanSlots.
     */
    @SuppressWarnings("unchecked")
    private static Cache<byte[], byte[]> getOrCreateCache(EmbeddedCacheManager cacheManager) {
        if (cacheManager.cacheExists(LRA_OBJECTSTORE_CACHE_NAME)) {
            return cacheManager.getCache(LRA_OBJECTSTORE_CACHE_NAME);
        }

        ConfigurationBuilder cacheConfig = new ConfigurationBuilder();
        cacheConfig
                .clustering()
                .cacheMode(getCacheMode())
                .partitionHandling()
                .whenSplit(org.infinispan.partitionhandling.PartitionHandling.DENY_READ_WRITES)
                .mergePolicy(org.infinispan.conflict.MergePolicy.PREFERRED_ALWAYS);

        cacheManager.defineConfiguration(LRA_OBJECTSTORE_CACHE_NAME, cacheConfig.build());
        return cacheManager.getCache(LRA_OBJECTSTORE_CACHE_NAME);
    }

    /**
     * Configures the ObjectStoreEnvironmentBean to use SlotStoreAdaptor.
     */
    private static void configureObjectStoreBean() {
        ObjectStoreEnvironmentBean defaultBean = BeanPopulator.getDefaultInstance(ObjectStoreEnvironmentBean.class);
        defaultBean.setObjectStoreType(SLOT_STORE_ADAPTOR);

        LRALogger.logger.debugf("ObjectStore type set to: %s", SLOT_STORE_ADAPTOR);
    }

    /**
     * Configures the SlotStoreEnvironmentBean and InfinispanStoreEnvironmentBean
     * to use InfinispanSlots with the provided cache.
     *
     * <p>
     * Uses reflection to avoid compile-time dependency on classes from
     * Narayana PR #2537 which may not be available yet.
     * </p>
     */
    private static void configureSlotStoreBean(Cache<byte[], byte[]> cache) throws Exception {
        // Configure SlotStoreEnvironmentBean
        Class<?> slotEnvClass = Class.forName(SLOT_STORE_ENV_BEAN);
        Object slotEnvBean = BeanPopulator.getDefaultInstance(
                slotEnvClass.asSubclass(Object.class));

        // Set number of slots (max concurrent LRAs)
        int numberOfSlots = getNumberOfSlots();
        Method setNumberOfSlots = slotEnvClass.getMethod("setNumberOfSlots", int.class);
        setNumberOfSlots.invoke(slotEnvBean, numberOfSlots);

        // Set the BackingSlots class name
        Method setBackingSlotsClassName = slotEnvClass.getMethod("setBackingSlotsClassName", String.class);
        setBackingSlotsClassName.invoke(slotEnvBean, INFINISPAN_SLOTS);

        LRALogger.logger.debugf("SlotStore configured: backingSlots=%s, numberOfSlots=%d",
                INFINISPAN_SLOTS, numberOfSlots);

        // Configure InfinispanStoreEnvironmentBean (extends SlotStoreEnvironmentBean)
        Class<?> ispnEnvClass = Class.forName(INFINISPAN_STORE_ENV_BEAN);
        Object ispnEnvBean = BeanPopulator.getDefaultInstance(
                ispnEnvClass.asSubclass(Object.class));

        // Set the cache
        Method setCache = ispnEnvClass.getMethod("setCache", Cache.class);
        setCache.invoke(ispnEnvBean, cache);

        // Set node address for ClusterMemberId key generation.
        // Each node generates unique cache keys: {groupId}:nodeId:uid:slotIndex
        // This enables multi-node HA: cache.keySet() returns all keys from all
        // nodes, so a fresh SlotStore can rebuild a complete index for recovery.
        String nodeId = System.getProperty("lra.coordinator.node.id", getHostname());
        Method setNodeAddress = ispnEnvClass.getMethod("setNodeAddress", String.class);
        setNodeAddress.invoke(ispnEnvBean, nodeId);

        // Set group name for Infinispan key grouping/affinity
        String groupName = System.getProperty("lra.coordinator.cluster.name", "lra");
        Method setGroupName = ispnEnvClass.getMethod("setGroupName", String.class);
        setGroupName.invoke(ispnEnvBean, groupName);

        // Set key generator to ClusterMemberId
        Method setKeyGenClass = ispnEnvClass.getMethod("setSlotKeyGeneratorClassName", String.class);
        setKeyGenClass.invoke(ispnEnvBean, CLUSTER_MEMBER_ID);

        LRALogger.logger.debugf("InfinispanSlots configured: cache=%s, nodeId=%s, groupName=%s, keyGenerator=%s",
                LRA_OBJECTSTORE_CACHE_NAME, nodeId, groupName, CLUSTER_MEMBER_ID);
    }

    private static String getHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Gets the configured number of slots (max concurrent LRAs).
     */
    private static int getNumberOfSlots() {
        String value = System.getProperty("lra.coordinator.slots.max");
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                LRALogger.logger.warnf("Invalid lra.coordinator.slots.max value: %s, using default %d",
                        value, DEFAULT_NUMBER_OF_SLOTS);
            }
        }
        return DEFAULT_NUMBER_OF_SLOTS;
    }

    /**
     * Gets the cache mode from configuration.
     */
    private static CacheMode getCacheMode() {
        String mode = System.getProperty("lra.coordinator.infinispan.cache.mode", "REPL_SYNC");
        try {
            return CacheMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            LRALogger.logger.warnf("Invalid cache mode '%s', using REPL_SYNC", mode);
            return CacheMode.REPL_SYNC;
        }
    }
}
