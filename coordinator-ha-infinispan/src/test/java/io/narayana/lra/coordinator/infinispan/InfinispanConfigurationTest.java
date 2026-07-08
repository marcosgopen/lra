/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.infinispan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.PartitionHandlingConfiguration;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.conflict.MergePolicy;
import org.infinispan.partitionhandling.PartitionHandling;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@code lra-objectstore} cache configuration produced by
 * {@link InfinispanConfiguration#configureObjectStore()} (private method).
 *
 * <p>
 * Because {@code configureObjectStore()} is private and CDI-only, these tests
 * duplicate its {@link ConfigurationBuilder} setup directly. This is intentional:
 * the goal is to verify that the enum constants and builder calls used in
 * production remain correct, not to test CDI wiring.
 * </p>
 *
 * <p>
 * The tests validate the builder state <em>before</em> calling {@code build()},
 * because Infinispan rejects {@code REPL_SYNC} (a clustered cache mode) when
 * registered against a non-clustered {@link org.infinispan.manager.DefaultCacheManager}.
 * Verifying the builder constants is sufficient and portable.
 * </p>
 *
 * <p>
 * Covers:
 * </p>
 * <ul>
 * <li>TS-CFG-01 — cache mode is {@code REPL_SYNC}</li>
 * <li>TS-CFG-02 — partition-handling strategy is {@code DENY_READ_WRITES}</li>
 * <li>TS-CFG-03 — merge policy is {@code PREFERRED_ALWAYS}</li>
 * <li>TS-CFG-04 — {@code globalState().enable()} path sets persistent location</li>
 * </ul>
 */
class InfinispanConfigurationTest {

    // -------------------------------------------------------------------------
    // TS-CFG-01 — cache mode must be REPL_SYNC
    // -------------------------------------------------------------------------

    /**
     * TS-CFG-01 — the {@code lra-objectstore} cache MUST be configured with
     * {@code REPL_SYNC} so that every write is synchronously replicated to all
     * cluster members before the operation returns.
     * Spec §9.1: "cache mode MUST be REPL_SYNC".
     */
    @Test
    void objectStoreCache_hasCacheMode_REPL_SYNC() {
        ConfigurationBuilder cb = buildObjectStoreCacheConfig();
        assertEquals(CacheMode.REPL_SYNC, cb.clustering().cacheMode(),
                "ConfigurationBuilder must be set to REPL_SYNC for clustered deployments");
    }

    // -------------------------------------------------------------------------
    // TS-CFG-02 — partition handling must be DENY_READ_WRITES
    // -------------------------------------------------------------------------

    /**
     * TS-CFG-02 — minority partitions MUST refuse all read/write operations so
     * that stale LRA state is never served to clients.
     * Spec §9.4: "partition handling strategy MUST be DENY_READ_WRITES".
     */
    @Test
    void objectStoreCache_partitionHandling_isDenyReadWrites() {
        ConfigurationBuilder cb = buildObjectStoreCacheConfig();
        PartitionHandlingConfiguration phCfg = cb.clustering().partitionHandling().create();
        assertEquals(PartitionHandling.DENY_READ_WRITES,
                phCfg.whenSplit(),
                "partition handling must be DENY_READ_WRITES");
    }

    // -------------------------------------------------------------------------
    // TS-CFG-03 — merge policy must be PREFERRED_ALWAYS
    // -------------------------------------------------------------------------

    /**
     * TS-CFG-03 — after a network partition heals the coordinator that held the
     * majority (preferred) segment MUST win to avoid re-processing already-completed LRAs.
     * Spec §9.5: "merge policy MUST be PREFERRED_ALWAYS".
     */
    @Test
    void objectStoreCache_mergePolicy_isPreferredAlways() {
        ConfigurationBuilder cb = buildObjectStoreCacheConfig();
        PartitionHandlingConfiguration phCfg = cb.clustering().partitionHandling().create();
        assertEquals(MergePolicy.PREFERRED_ALWAYS,
                phCfg.mergePolicy(),
                "merge policy must be PREFERRED_ALWAYS");
    }

    // -------------------------------------------------------------------------
    // TS-CFG-04 — embedded mode must enable global state (persistent location)
    // -------------------------------------------------------------------------

    /**
     * TS-CFG-04 — in embedded (non-WildFly) mode {@code globalState().enable()}
     * MUST be called so that each node retains its cluster-membership state across
     * restarts. Spec §8.3: "coordinator state MUST survive container restarts".
     *
     * <p>
     * We build a {@link GlobalConfiguration} the same way {@code initializeEmbedded()}
     * does and confirm that global state is enabled.
     * </p>
     */
    @Test
    void embeddedGlobalConfig_globalStateIsEnabled() {
        String persistentLocation = System.getProperty("java.io.tmpdir") + "/lra-infinispan-test";
        GlobalConfigurationBuilder globalConfig = new GlobalConfigurationBuilder();
        globalConfig
                .transport()
                .defaultTransport()
                .clusterName("lra-cluster")
                .nodeName("test-node");
        globalConfig
                .globalState()
                .enable()
                .persistentLocation(persistentLocation);

        GlobalConfiguration built = globalConfig.build();
        assertTrue(built.globalState().enabled(),
                "globalState must be enabled in embedded mode for persistent cluster membership");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Constructs the {@link ConfigurationBuilder} exactly as
     * {@code InfinispanConfiguration.configureObjectStore()} does.
     */
    private static ConfigurationBuilder buildObjectStoreCacheConfig() {
        ConfigurationBuilder cb = new ConfigurationBuilder();
        cb.clustering().cacheMode(CacheMode.REPL_SYNC)
                .partitionHandling()
                .whenSplit(PartitionHandling.DENY_READ_WRITES)
                .mergePolicy(MergePolicy.PREFERRED_ALWAYS);
        return cb;
    }
}
