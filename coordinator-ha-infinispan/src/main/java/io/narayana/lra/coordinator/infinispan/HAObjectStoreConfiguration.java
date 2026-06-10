/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.infinispan;

import com.arjuna.ats.arjuna.common.ObjectStoreEnvironmentBean;
import com.arjuna.ats.arjuna.objectstore.StoreManager;
import com.arjuna.ats.internal.arjuna.objectstore.slot.SlotStoreAdaptor;
import com.arjuna.ats.internal.arjuna.objectstore.slot.SlotStoreEnvironmentBean;
import com.arjuna.ats.internal.arjuna.objectstore.slot.infinispan.InfinispanSlots;
import com.arjuna.ats.internal.arjuna.objectstore.slot.infinispan.InfinispanStoreEnvironmentBean;
import com.arjuna.common.internal.util.propertyservice.BeanPopulator;
import io.narayana.lra.logging.LRALogger;
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
 */
public class HAObjectStoreConfiguration {

    static final String LRA_OBJECTSTORE_CACHE_NAME = "lra-objectstore";

    private static final int DEFAULT_NUMBER_OF_SLOTS = 10000;

    private HAObjectStoreConfiguration() {
    }

    /**
     * Configures the Narayana ObjectStore to use InfinispanSlots.
     *
     * <p>
     * This method:
     * </p>
     * <ol>
     * <li>Creates a replicated Infinispan cache for LRA state storage</li>
     * <li>Configures SlotStoreAdaptor as the ObjectStore implementation</li>
     * <li>Wires the Infinispan cache into InfinispanStoreEnvironmentBean</li>
     * </ol>
     *
     * @param cacheManager the Infinispan EmbeddedCacheManager (must be initialized)
     * @return true if InfinispanSlots was successfully configured, false on failure
     */
    public static boolean configure(EmbeddedCacheManager cacheManager) {
        if (cacheManager == null) {
            LRALogger.logger.warn("Cannot configure HA ObjectStore: cache manager is null");
            return false;
        }

        try {
            Cache<byte[], byte[]> cache = getOrCreateCache(cacheManager);

            // configureObjectStoreBean() is called LAST so that if configureSlotStoreBean()
            // fails (e.g. InfinispanSlots not available in this Narayana version), the
            // ObjectStore type is NOT changed and the filesystem store remains functional.
            configureSlotStoreBean(cache);

            configureObjectStoreBean();

            try {
                StoreManager.shutdown();
            } catch (Exception e) {
                LRALogger.logger.debugf("StoreManager.shutdown() during configure: %s", e.getMessage());
            }

            LRALogger.logger.info("HA ObjectStore configured: SlotStoreAdaptor -> InfinispanSlots -> "
                    + LRA_OBJECTSTORE_CACHE_NAME + " cache (replicated)");
            return true;

        } catch (Throwable t) {
            LRALogger.logger.warnf(t, "Failed to configure HA ObjectStore (InfinispanSlots may not be "
                    + "available in this Narayana version). "
                    + "Falling back to default filesystem-based ObjectStore. "
                    + "LRA state will NOT be replicated across the cluster.");
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
        String storeType = SlotStoreAdaptor.class.getName();

        BeanPopulator.getDefaultInstance(ObjectStoreEnvironmentBean.class)
                .setObjectStoreType(storeType);
        BeanPopulator.getNamedInstance(ObjectStoreEnvironmentBean.class, "stateStore")
                .setObjectStoreType(storeType);
        BeanPopulator.getNamedInstance(ObjectStoreEnvironmentBean.class, "communicationStore")
                .setObjectStoreType(storeType);

        LRALogger.logger.debugf("ObjectStore type set to: %s", storeType);
    }

    /**
     * Configures the SlotStoreEnvironmentBean and InfinispanStoreEnvironmentBean
     * to use InfinispanSlots with the provided cache.
     */
    private static void configureSlotStoreBean(Cache<byte[], byte[]> cache) {
        SlotStoreEnvironmentBean slotEnvBean = BeanPopulator.getDefaultInstance(SlotStoreEnvironmentBean.class);

        int numberOfSlots = getNumberOfSlots();
        slotEnvBean.setNumberOfSlots(numberOfSlots);
        slotEnvBean.setBackingSlotsClassName(InfinispanSlots.class.getName());

        LRALogger.logger.debugf("SlotStore configured: backingSlots=%s, numberOfSlots=%d",
                InfinispanSlots.class.getName(), numberOfSlots);

        InfinispanStoreEnvironmentBean ispnEnvBean = BeanPopulator.getDefaultInstance(InfinispanStoreEnvironmentBean.class);

        ispnEnvBean.setCache(cache);

        String nodeId = System.getProperty("lra.coordinator.node.id", getHostname());
        ispnEnvBean.setNodeAddress(nodeId);

        LRALogger.logger.debugf("InfinispanSlots configured: cache=%s, nodeId=%s",
                LRA_OBJECTSTORE_CACHE_NAME, nodeId);
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
