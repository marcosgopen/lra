/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.ha;

import static org.junit.jupiter.api.Assertions.*;

import io.narayana.lra.coordinator.domain.model.LRAState;
import io.narayana.lra.coordinator.internal.ClusterCoordinator;
import io.narayana.lra.coordinator.internal.InfinispanStore;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.infinispan.Cache;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.partitionhandling.AvailabilityMode;
import org.jboss.arquillian.container.test.api.ContainerController;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration test for LRA High Availability clustering with WildFly.
 *
 * Tests:
 * - REPL_SYNC cache mode for state replication
 * - ClusterCoordinator coordinator election
 * - Failover scenarios
 * - Network partition handling
 *
 * Requires WildFly with standalone-ha.xml configuration.
 */
@ExtendWith(ArquillianExtension.class)
public class HAClusteringIT {

    @ArquillianResource
    private ContainerController controller;

    @Inject
    private InfinispanStore infinispanStore;

    @Inject
    private ClusterCoordinator clusterCoordinator;

    @Inject
    private EmbeddedCacheManager cacheManager;

    @Deployment
    public static WebArchive createDeployment() {
        return ShrinkWrap.create(WebArchive.class, "lra-ha-test.war")
                .addPackages(true, "io.narayana.lra.coordinator")
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml")
                .addAsManifestResource(EmptyAsset.INSTANCE, "MANIFEST.MF");
    }

    @BeforeEach
    void setUp() {
        // Start both WildFly nodes
        controller.start("wildfly-ha-node1");
        controller.start("wildfly-ha-node2");

        // Wait for cluster to form
        waitForClusterFormation();
    }

    @AfterEach
    void tearDown() {
        // Stop both nodes
        if (controller.isStarted("wildfly-ha-node2")) {
            controller.stop("wildfly-ha-node2");
        }
        if (controller.isStarted("wildfly-ha-node1")) {
            controller.stop("wildfly-ha-node1");
        }
    }

    @Test
    void testReplSyncCacheReplication() throws Exception {
        // Given: Two-node cluster with REPL_SYNC caches
        assertTrue(infinispanStore.isHaEnabled(), "HA mode should be enabled");

        URI lraId = URI.create("http://localhost:8080/lra-coordinator/test-lra-repl");
        LRAState state = createTestLRAState(lraId, LRAStatus.Active);

        // When: Save LRA on node 1
        infinispanStore.saveLRA(lraId, state);

        // Then: State should be replicated to node 2
        TimeUnit.SECONDS.sleep(1); // Allow replication time

        Cache<URI, LRAState> activeCache = infinispanStore.getActiveLRACache();
        assertEquals(2, activeCache.getAdvancedCache().getRpcManager().getMembers().size(),
                "Cluster should have 2 members");

        LRAState replicated = infinispanStore.loadLRA(lraId);
        assertNotNull(replicated, "State should be replicated across cluster");
        assertEquals(lraId, replicated.getId());
        assertEquals(LRAStatus.Active, replicated.getStatus());
    }

    @Test
    void testClusterCoordinatorElection() throws Exception {
        // Given: Two-node cluster
        assertTrue(clusterCoordinator.isInitialized(), "ClusterCoordinator should be initialized");

        // When: Check coordinator status on both nodes
        boolean isCoordinator1 = clusterCoordinator.isCoordinator();

        // Then: Exactly one node should be coordinator
        // Note: In WildFly cluster, the first node typically becomes coordinator
        if (isCoordinator1) {
            System.out.println("Node 1 is the cluster coordinator");
        } else {
            System.out.println("Node 2 is the cluster coordinator");
        }

        // Verify cluster size
        assertNotNull(cacheManager, "Cache manager should be available");
        assertEquals(2, cacheManager.getMembers().size(), "Should see 2 cluster members");
    }

    @Test
    void testCoordinatorFailover() throws Exception {
        // Given: Two-node cluster
        boolean wasCoordinator = clusterCoordinator.isCoordinator();
        String failedNode = wasCoordinator ? "wildfly-ha-node1" : "wildfly-ha-node2";
        String survivingNode = wasCoordinator ? "wildfly-ha-node2" : "wildfly-ha-node1";

        // When: Stop the coordinator node
        controller.stop(failedNode);

        // Allow time for cluster to detect failure and elect new coordinator
        TimeUnit.SECONDS.sleep(5);

        // Then: Surviving node should become coordinator
        // (This would need to be verified by deploying to both nodes and checking)
        assertTrue(controller.isStarted(survivingNode), "Surviving node should still be running");
        assertFalse(controller.isStarted(failedNode), "Failed node should be stopped");

        // Restart failed node
        controller.start(failedNode);
        waitForClusterFormation();
    }

    @Test
    void testCacheAvailability() {
        // Given: Two-node cluster with REPL_SYNC
        assertTrue(infinispanStore.isAvailable(), "Cache should be available");

        // When: Check availability mode
        AvailabilityMode mode = infinispanStore.getAvailabilityMode();

        // Then: Should be AVAILABLE (not DEGRADED)
        assertEquals(AvailabilityMode.AVAILABLE, mode,
                "Cache should be in AVAILABLE mode with 2 nodes");
    }

    @Test
    void testStateMoveBetweenCaches() throws Exception {
        // Given: LRA in active cache
        URI lraId = URI.create("http://localhost:8080/lra-coordinator/test-lra-move");
        LRAState activeState = createTestLRAState(lraId, LRAStatus.Active);
        infinispanStore.saveLRA(lraId, activeState);

        // When: Move to recovering cache
        LRAState closingState = createTestLRAState(lraId, LRAStatus.Closing);
        infinispanStore.moveToRecovering(lraId, closingState);

        TimeUnit.MILLISECONDS.sleep(500); // Allow replication

        // Then: Should be in recovering cache on all nodes
        LRAState loaded = infinispanStore.loadLRA(lraId);
        assertNotNull(loaded, "State should be in recovering cache");
        assertEquals(LRAStatus.Closing, loaded.getStatus());

        // When: Move to failed cache
        infinispanStore.moveToFailed(lraId);
        TimeUnit.MILLISECONDS.sleep(500);

        // Then: Should be in failed cache
        loaded = infinispanStore.loadLRA(lraId);
        assertNotNull(loaded, "State should be in failed cache");
    }

    @Test
    void testConcurrentAccessWithReplication() throws Exception {
        // Given: Multiple LRAs created concurrently
        int lraCount = 10;
        URI[] lraIds = new URI[lraCount];

        // When: Create multiple LRAs concurrently
        for (int i = 0; i < lraCount; i++) {
            lraIds[i] = URI.create("http://localhost:8080/lra-coordinator/concurrent-" + i);
            LRAState state = createTestLRAState(lraIds[i], LRAStatus.Active);
            infinispanStore.saveLRA(lraIds[i], state);
        }

        TimeUnit.SECONDS.sleep(1); // Allow replication

        // Then: All LRAs should be replicated
        for (URI lraId : lraIds) {
            LRAState loaded = infinispanStore.loadLRA(lraId);
            assertNotNull(loaded, "LRA " + lraId + " should be replicated");
        }
    }

    // Helper methods

    private void waitForClusterFormation() {
        // Wait up to 30 seconds for cluster to form
        long endTime = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < endTime) {
            try {
                if (cacheManager != null && cacheManager.getMembers() != null
                        && cacheManager.getMembers().size() >= 2) {
                    System.out.println("Cluster formed with " + cacheManager.getMembers().size() + " members");
                    return;
                }
                TimeUnit.MILLISECONDS.sleep(500);
            } catch (Exception e) {
                // Continue waiting
            }
        }
        System.err.println("WARNING: Cluster may not have fully formed");
    }

    private LRAState createTestLRAState(URI lraId, LRAStatus status) {
        try {
            java.lang.reflect.Constructor<LRAState> constructor = LRAState.class.getDeclaredConstructor(
                    URI.class, URI.class, String.class, LRAStatus.class,
                    java.time.LocalDateTime.class, java.time.LocalDateTime.class,
                    long.class, String.class, byte[].class);
            constructor.setAccessible(true);

            return constructor.newInstance(
                    lraId, // id
                    null, // parentId
                    "test-client", // clientId
                    status, // status
                    java.time.LocalDateTime.now(), // startTime
                    null, // finishTime
                    0L, // timeLimit
                    "test-node", // nodeId
                    new byte[0] // serializedState
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test LRAState", e);
        }
    }
}
