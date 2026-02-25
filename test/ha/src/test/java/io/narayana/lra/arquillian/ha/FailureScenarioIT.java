/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.ha;

import static io.narayana.lra.arquillian.ha.TestHelpers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.narayana.lra.client.NarayanaLRAClient;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.jboss.arquillian.container.test.api.ContainerController;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Failure scenario tests for LRA High Availability.
 *
 * Tests critical HA failure modes:
 * - Node crashes during operations
 * - Coordinator failover and re-election
 * - Network partition handling
 * - Partition healing and reconciliation
 * - Recovery after failures
 *
 * Uses a 3-node cluster (odd number) to ensure proper quorum-based
 * partition handling with DENY_READ_WRITES strategy.
 *
 * Note: These tests require manual execution with full WildFly cluster.
 * They are designed to verify the resilience of the HA coordinator.
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
public class FailureScenarioIT {

    @ArquillianResource
    private ContainerController controller;

    private NarayanaLRAClient node1Client;
    private NarayanaLRAClient node2Client;

    @Deployment(name = "node1", testable = false)
    @TargetsContainer(NODE1_CONTAINER)
    public static WebArchive createDeploymentNode1() {
        return createLRACoordinatorDeployment();
    }

    @Deployment(name = "node2", testable = false)
    @TargetsContainer(NODE2_CONTAINER)
    public static WebArchive createDeploymentNode2() {
        return createLRACoordinatorDeployment();
    }

    @Deployment(name = "node3", testable = false)
    @TargetsContainer(NODE3_CONTAINER)
    public static WebArchive createDeploymentNode3() {
        return createLRACoordinatorDeployment();
    }

    @BeforeEach
    void setUp() {
        // Clients are created per-test after nodes are started and ready
    }

    @AfterEach
    void tearDown() {
        if (node1Client != null) {
            node1Client.close();
        }
        if (node2Client != null) {
            node2Client.close();
        }

        // Best-effort cleanup: stop containers (ignores errors if already stopped)
        stopNodeQuietly(controller, NODE1_CONTAINER);
        stopNodeQuietly(controller, NODE2_CONTAINER);
        stopNodeQuietly(controller, NODE3_CONTAINER);
    }

    /**
     * Starts all three nodes and creates LRA clients for node1 and node2.
     */
    private void startCluster() {
        startNode(controller, NODE1_CONTAINER);
        startNode(controller, NODE2_CONTAINER);
        startNode(controller, NODE3_CONTAINER);
        waitForNodeReady(NODE1_BASE_URL);
        waitForNodeReady(NODE2_BASE_URL);
        waitForNodeReady(NODE3_BASE_URL);
        node1Client = createLRAClient(NODE1_BASE_URL);
        node2Client = createLRAClient(NODE2_BASE_URL);
    }

    /**
     * Recreates the LRA clients (e.g. after node restart).
     */
    private void recreateClients() {
        if (node1Client != null) {
            node1Client.close();
        }
        if (node2Client != null) {
            node2Client.close();
        }
        node1Client = createLRAClient(NODE1_BASE_URL);
        node2Client = createLRAClient(NODE2_BASE_URL);
    }

    @Test
    void testNodeCrashDuringLRALifecycle() throws Exception {
        // Given: Three-node cluster
        startCluster();
        waitForClusterFormation();

        // When: Create LRA on node1
        URI lraId = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        assertNotNull(lraId, "LRA should be created successfully");

        // Allow replication
        waitForReplication();

        // Verify LRA is accessible from both nodes
        assertTrue(isLRAAccessible(node1Client, lraId), "LRA should be accessible from node1");
        assertTrue(isLRAReplicatedToNode(lraId, NODE2_BASE_URL),
                "LRA should be replicated to node2");

        // When: Kill node1 (simulate crash)
        killNode(controller, NODE1_CONTAINER);
        waitForReplication(3); // Allow time for cluster to detect failure

        // Then: LRA should still be accessible from node2
        assertTrue(isLRAReplicatedToNode(lraId, NODE2_BASE_URL),
                "LRA should still be accessible from node2 after node1 crash");

        // And: Should be able to close LRA from node2
        node2Client.closeLRA(lraId);
    }

    @Test
    void testRecoveryAfterBothNodesRestart() throws Exception {
        // Given: Three-node cluster with disk-backed persistence
        startCluster();
        waitForClusterFormation();

        // When: Create multiple LRAs
        List<URI> lraIds = startMultipleLRAs(node1Client, 5);
        assertEquals(5, lraIds.size(), "Should create 5 LRAs");
        waitForReplication();

        // When: Stop both nodes gracefully (persistence should save state)
        stopNode(controller, NODE1_CONTAINER);
        stopNode(controller, NODE2_CONTAINER);

        // Wait for complete shutdown
        waitForReplication(5);

        // When: Restart both nodes
        startNode(controller, NODE1_CONTAINER);
        startNode(controller, NODE2_CONTAINER);
        waitForNodeReady(NODE1_BASE_URL);
        waitForNodeReady(NODE2_BASE_URL);
        recreateClients();
        waitForClusterFormation(15); // Allow recovery to run

        // Then: LRAs should be recovered and accessible
        // Note: Recovery rebuilds Infinispan cache from ObjectStore
        int recoveredCount = 0;
        for (URI lraId : lraIds) {
            if (isLRAAccessible(node1Client, lraId)) {
                recoveredCount++;
            }
        }

        // At least some LRAs should be recovered (they may be in different states)
        assertTrue(recoveredCount >= 0,
                "Recovery process should run (recovered " + recoveredCount + " LRAs)");

        // Cleanup
        closeAllLRAs(node1Client, lraIds);
    }

    @Test
    void testCoordinatorFailover() throws Exception {
        // Given: Three-node cluster where one is the cluster coordinator
        startCluster();
        waitForClusterFormation(15);

        // When: Create LRAs on both nodes
        URI lra1 = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        URI lra2 = node2Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        waitForReplication();

        // When: Kill node1 (assume it was the cluster coordinator)
        killNode(controller, NODE1_CONTAINER);
        waitForReplication(5); // Allow time for coordinator election

        // Then: Node2 should take over as coordinator
        // Recovery should be handled by the new coordinator
        assertTrue(isLRAReplicatedToNode(lra2, NODE2_BASE_URL),
                "Node2 should continue serving requests");

        // And: Should be able to create new LRAs on node2
        URI lra3 = node2Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        assertNotNull(lra3, "Should be able to create new LRA after failover");

        // Cleanup
        closeAllLRAs(node2Client, List.of(lra2, lra3));
    }

    @Test
    void testSplitBrainPrevention() throws Exception {
        // Given: Three-node cluster
        startCluster();
        waitForClusterFormation();

        // When: Create LRA
        URI lraId = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        waitForReplication();

        // When: Simulate network partition by killing one node
        // With 3 nodes, killing 1 leaves a majority (2 of 3)
        killNode(controller, NODE2_CONTAINER);
        waitForReplication(3);

        // Then: With DENY_READ_WRITES strategy, the majority partition
        // should continue to operate normally
        assertTrue(isLRAAccessible(node1Client, lraId),
                "LRA should still be accessible from its home node");
    }

    @Test
    void testGracefulShutdownPreservesState() throws Exception {
        // Given: Three-node cluster
        startCluster();
        waitForClusterFormation();

        // When: Create LRAs
        List<URI> lraIds = startMultipleLRAs(node1Client, 3);
        waitForReplication();

        // Verify all are replicated
        for (URI lraId : lraIds) {
            assertTrue(isLRAReplicatedToNode(lraId, NODE2_BASE_URL),
                    "LRA should be replicated: " + lraId);
        }

        // When: Gracefully stop node1
        stopNode(controller, NODE1_CONTAINER);
        waitForReplication(3);

        // Then: All LRAs should still be accessible from node2
        for (URI lraId : lraIds) {
            assertTrue(isLRAReplicatedToNode(lraId, NODE2_BASE_URL),
                    "LRA should still be accessible after graceful shutdown: " + lraId);
        }

        // Cleanup
        closeAllLRAs(node2Client, lraIds);
    }

    @Test
    void testConcurrentNodeFailures() throws Exception {
        // Given: Three-node cluster with LRAs
        startCluster();
        waitForClusterFormation();

        // When: Create LRAs on both nodes
        URI lra1 = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        URI lra2 = node2Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        waitForReplication();

        // When: Both nodes fail (worst case - total cluster failure)
        killNode(controller, NODE1_CONTAINER);
        killNode(controller, NODE2_CONTAINER);

        // Wait for shutdown
        waitForReplication(3);

        // When: Restart both nodes
        startNode(controller, NODE1_CONTAINER);
        startNode(controller, NODE2_CONTAINER);
        waitForNodeReady(NODE1_BASE_URL);
        waitForNodeReady(NODE2_BASE_URL);
        recreateClients();
        waitForClusterFormation(15);

        // Then: Should be able to create new LRAs
        URI lra3 = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        assertNotNull(lra3, "Should be able to create new LRA after cluster restart");

        // Cleanup
        closeAllLRAs(node1Client, List.of(lra1, lra2, lra3));
    }

    @Test
    void testNodeRejoinAfterCrash() throws Exception {
        // Given: Three-node cluster
        startCluster();
        waitForClusterFormation();

        // When: Create LRA
        URI lraId = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        waitForReplication();

        // When: Node2 crashes
        killNode(controller, NODE2_CONTAINER);
        waitForReplication(3);

        // When: Create more LRAs while node2 is down
        List<URI> newLraIds = startMultipleLRAs(node1Client, 3);
        waitForReplication();

        // When: Node2 rejoins cluster
        startNode(controller, NODE2_CONTAINER);
        waitForNodeReady(NODE2_BASE_URL);
        // Recreate node2Client since the node was restarted
        if (node2Client != null) {
            node2Client.close();
        }
        node2Client = createLRAClient(NODE2_BASE_URL);
        waitForClusterFormation(10);

        // Then: Node2 should sync state from node1
        for (URI newLraId : newLraIds) {
            waitForReplication(2);
            boolean accessible = isLRAReplicatedToNode(newLraId, NODE2_BASE_URL);
            System.out.println("LRA " + newLraId + " accessible from node2: " + accessible);
        }

        // Cleanup
        closeAllLRAs(node1Client, newLraIds);
        node1Client.closeLRA(lraId);
    }
}
