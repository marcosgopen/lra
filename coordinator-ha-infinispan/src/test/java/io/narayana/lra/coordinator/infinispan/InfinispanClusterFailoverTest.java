/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.infinispan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.narayana.lra.coordinator.internal.ClusterCoordinationService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Multi-node cluster coordinator failover tests.
 *
 * These tests create a 2-node Infinispan cluster and verify:
 * - First node in the JGroups view becomes coordinator
 * - When the coordinator leaves, the second node takes over
 * - Listeners are notified on coordinator changes
 */
class InfinispanClusterFailoverTest {

    private static final String CLUSTER_NAME = "lra-test-cluster-" + System.nanoTime();

    private EmbeddedCacheManager cacheManager1;
    private EmbeddedCacheManager cacheManager2;
    private InfinispanClusterCoordinator coordinator1;
    private InfinispanClusterCoordinator coordinator2;

    @AfterEach
    void tearDown() {
        if (coordinator1 != null) {
            coordinator1.shutdown();
        }
        if (coordinator2 != null) {
            coordinator2.shutdown();
        }
        if (cacheManager2 != null) {
            cacheManager2.stop();
        }
        if (cacheManager1 != null) {
            cacheManager1.stop();
        }
    }

    private EmbeddedCacheManager createClusteredCacheManager(String nodeName) {
        System.setProperty("jgroups.bind_addr", "127.0.0.1");

        GlobalConfigurationBuilder globalConfig = new GlobalConfigurationBuilder();
        globalConfig
                .transport()
                .defaultTransport()
                .clusterName(CLUSTER_NAME)
                .nodeName(nodeName);

        EmbeddedCacheManager manager = new DefaultCacheManager(globalConfig.build());

        // Define a replicated cache so nodes actually form a cluster
        ConfigurationBuilder cacheConfig = new ConfigurationBuilder();
        cacheConfig.clustering().cacheMode(CacheMode.REPL_SYNC);
        manager.defineConfiguration("test-cache", cacheConfig.build());
        manager.getCache("test-cache");

        return manager;
    }

    @Test
    void firstNodeInClusterIsCoordinator() {
        cacheManager1 = createClusteredCacheManager("node-1");

        coordinator1 = new InfinispanClusterCoordinator();
        coordinator1.initialize(cacheManager1);

        assertTrue(coordinator1.isCoordinator(),
                "First (and only) node should be the coordinator");
    }

    @Test
    void secondNodeJoiningIsNotCoordinator() throws InterruptedException {
        cacheManager1 = createClusteredCacheManager("node-1");
        coordinator1 = new InfinispanClusterCoordinator();
        coordinator1.initialize(cacheManager1);

        // Second node joins the cluster
        cacheManager2 = createClusteredCacheManager("node-2");
        coordinator2 = new InfinispanClusterCoordinator();
        coordinator2.initialize(cacheManager2);

        // Wait for cluster view to stabilize
        waitForClusterSize(cacheManager1, 2, 10);

        // First node should still be coordinator
        assertTrue(coordinator1.isCoordinator(),
                "First node should remain coordinator after second node joins");

        // Second node should NOT be coordinator
        assertFalse(coordinator2.isCoordinator(),
                "Second node should not be coordinator");
    }

    @Test
    void coordinatorFailoverWhenFirstNodeLeaves() throws InterruptedException {
        cacheManager1 = createClusteredCacheManager("node-1");
        coordinator1 = new InfinispanClusterCoordinator();
        coordinator1.initialize(cacheManager1);

        cacheManager2 = createClusteredCacheManager("node-2");
        coordinator2 = new InfinispanClusterCoordinator();

        // Track when node-2 becomes coordinator
        CountDownLatch becameCoordinatorLatch = new CountDownLatch(1);
        coordinator2.addCoordinatorChangeListener(
                new ClusterCoordinationService.CoordinatorChangeListener() {
                    @Override
                    public void onBecameCoordinator() {
                        becameCoordinatorLatch.countDown();
                    }

                    @Override
                    public void onLostCoordinator() {
                    }
                });
        coordinator2.initialize(cacheManager2);

        // Wait for cluster to form
        waitForClusterSize(cacheManager1, 2, 10);

        assertFalse(coordinator2.isCoordinator(),
                "Node-2 should not be coordinator while node-1 is alive");

        // Simulate node-1 failure by stopping its cache manager
        coordinator1.shutdown();
        cacheManager1.stop();
        cacheManager1 = null;

        // Node-2 should become coordinator via JGroups view change
        assertTrue(becameCoordinatorLatch.await(30, TimeUnit.SECONDS),
                "Node-2 should become coordinator after node-1 leaves");
        assertTrue(coordinator2.isCoordinator(),
                "Node-2 should report as coordinator");
    }

    @Test
    void listenerNotifiedWhenLosingCoordinator() throws InterruptedException {
        // Start node-1 as coordinator
        cacheManager1 = createClusteredCacheManager("node-1");
        coordinator1 = new InfinispanClusterCoordinator();

        // We need to track when node-1 LOSES coordinator status.
        // This can't happen in a 2-node cluster unless node-1 is explicitly
        // demoted (which JGroups doesn't do). Instead we test that the
        // lostCoordinator listener works by verifying it's NOT called
        // when a non-coordinator node leaves.
        CountDownLatch lostLatch = new CountDownLatch(1);
        coordinator1.addCoordinatorChangeListener(
                new ClusterCoordinationService.CoordinatorChangeListener() {
                    @Override
                    public void onBecameCoordinator() {
                    }

                    @Override
                    public void onLostCoordinator() {
                        lostLatch.countDown();
                    }
                });
        coordinator1.initialize(cacheManager1);

        // Add node-2
        cacheManager2 = createClusteredCacheManager("node-2");
        coordinator2 = new InfinispanClusterCoordinator();
        coordinator2.initialize(cacheManager2);
        waitForClusterSize(cacheManager1, 2, 10);

        // Remove node-2 (the non-coordinator)
        coordinator2.shutdown();
        cacheManager2.stop();
        cacheManager2 = null;

        // node-1 should NOT lose coordinator status
        assertFalse(lostLatch.await(5, TimeUnit.SECONDS),
                "Node-1 should NOT lose coordinator status when a follower leaves");
        assertTrue(coordinator1.isCoordinator());
    }

    private void waitForClusterSize(EmbeddedCacheManager manager, int expectedSize, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (manager.getMembers() != null && manager.getMembers().size() == expectedSize) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError(String.format("Cluster did not reach size %d within %ds (current: %s)",
                expectedSize, timeoutSeconds,
                manager.getMembers() != null ? manager.getMembers().size() : "null"));
    }
}
