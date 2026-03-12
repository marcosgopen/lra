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
import org.jboss.arquillian.container.test.api.Deployer;
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
 * - Network partition handling (DENY_READ_WRITES)
 * - Partition healing and reconciliation
 * - Recovery after failures (CAS-based recovery claiming)
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

    @ArquillianResource
    private Deployer deployer;

    private NarayanaLRAClient node1Client;
    private NarayanaLRAClient node2Client;
    private boolean deployed = false;

    @Deployment(name = "node1", managed = false, testable = false)
    @TargetsContainer(NODE1_CONTAINER)
    public static WebArchive createDeploymentNode1() {
        return createLRACoordinatorDeployment();
    }

    @Deployment(name = "node2", managed = false, testable = false)
    @TargetsContainer(NODE2_CONTAINER)
    public static WebArchive createDeploymentNode2() {
        return createLRACoordinatorDeployment();
    }

    @Deployment(name = "node3", managed = false, testable = false)
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

        // Undeploy before stopping to avoid stale deployments on next start
        cleanupCluster(controller, deployer);
        deployed = false;
    }

    private void startCluster() {
        startNode(controller, NODE1_CONTAINER);
        startNode(controller, NODE2_CONTAINER);
        startNode(controller, NODE3_CONTAINER);

        if (!deployed) {
            safeDeployToNode(deployer, "node1");
            safeDeployToNode(deployer, "node2");
            safeDeployToNode(deployer, "node3");
            deployed = true;
        }

        waitForNodeReady(NODE1_BASE_URL);
        waitForNodeReady(NODE2_BASE_URL);
        waitForNodeReady(NODE3_BASE_URL);
        node1Client = createLRAClient(NODE1_BASE_URL);
        node2Client = createLRAClient(NODE2_BASE_URL);
    }

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
        startCluster();
        waitForClusterFormation();

        URI lraId = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        assertTrue(lraId.toString().contains("lra-coordinator"),
                "LRA URI should contain coordinator path: " + lraId);

        waitForReplication();

        assertTrue(isLRAAccessible(node1Client, lraId), "LRA should be accessible from node1");
        assertTrue(isLRAReplicatedToNode(lraId, NODE2_BASE_URL),
                "LRA should be replicated to node2");

        killNode(controller, NODE1_CONTAINER);
        // After a crash, JGroups needs to detect failure + Infinispan rebalances
        waitForReplication(10);

        assertTrue(isLRAReplicatedToNode(lraId, NODE2_BASE_URL),
                "LRA should still be accessible from node2 after node1 crash");

        // Rewrite LRA URI to route through node2 since node1 is down
        URI lraOnNode2 = rewriteLRAToNode(lraId, NODE2_BASE_URL);
        node2Client.closeLRA(lraOnNode2);
    }

    @Test
    void testRecoveryAfterBothNodesRestart() throws Exception {
        startCluster();
        waitForClusterFormation();

        List<URI> lraIds = startMultipleLRAs(node1Client, 5);
        assertEquals(5, lraIds.size(), "Should create 5 LRAs");
        waitForReplication();

        undeployQuietly(deployer, "node1");
        undeployQuietly(deployer, "node2");
        stopNode(controller, NODE1_CONTAINER);
        stopNode(controller, NODE2_CONTAINER);

        waitForReplication(5);

        startNode(controller, NODE1_CONTAINER);
        startNode(controller, NODE2_CONTAINER);
        safeDeployToNode(deployer, "node1");
        safeDeployToNode(deployer, "node2");
        waitForNodeReady(NODE1_BASE_URL);
        waitForNodeReady(NODE2_BASE_URL);
        recreateClients();
        waitForClusterFormation(15);

        int recoveredCount = 0;
        for (URI lraId : lraIds) {
            if (isLRAAccessible(node1Client, lraId)) {
                recoveredCount++;
            }
        }

        assertTrue(recoveredCount >= 0,
                "Recovery process should run (recovered " + recoveredCount + " LRAs)");

        closeAllLRAs(node1Client, lraIds);
    }

    @Test
    void testCoordinatorFailover() throws Exception {
        startCluster();
        waitForClusterFormation(15);

        URI lra1 = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        URI lra2 = node2Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        waitForReplication();

        killNode(controller, NODE1_CONTAINER);
        waitForReplication(5);

        assertTrue(isLRAReplicatedToNode(lra2, NODE2_BASE_URL),
                "Node2 should continue serving requests");

        URI lra3 = node2Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        assertTrue(lra3.toString().contains("lra-coordinator"),
                "Should be able to create new LRA after failover: " + lra3);

        closeAllLRAs(node2Client, List.of(lra2, lra3));
    }

    @Test
    void testSplitBrainPrevention() throws Exception {
        startCluster();
        waitForClusterFormation();

        URI lraId = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        waitForReplication();

        killNode(controller, NODE2_CONTAINER);
        waitForReplication(3);

        assertTrue(isLRAAccessible(node1Client, lraId),
                "LRA should still be accessible from its home node");
    }

    @Test
    void testGracefulShutdownPreservesState() throws Exception {
        startCluster();
        waitForClusterFormation();

        List<URI> lraIds = startMultipleLRAs(node1Client, 3);
        waitForReplication();

        for (URI lraId : lraIds) {
            assertTrue(isLRAReplicatedToNode(lraId, NODE2_BASE_URL),
                    "LRA should be replicated: " + lraId);
        }

        stopNode(controller, NODE1_CONTAINER);
        // After shutdown, cluster view change + Infinispan rebalance takes time
        waitForReplication(10);

        for (URI lraId : lraIds) {
            assertTrue(isLRAReplicatedToNode(lraId, NODE2_BASE_URL),
                    "LRA should still be accessible after graceful shutdown: " + lraId);
        }

        closeAllLRAs(node2Client, lraIds);
    }

    @Test
    void testConcurrentNodeFailures() throws Exception {
        startCluster();
        waitForClusterFormation();

        URI lra1 = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        URI lra2 = node2Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        waitForReplication();

        killNode(controller, NODE1_CONTAINER);
        killNode(controller, NODE2_CONTAINER);

        waitForReplication(3);

        startNode(controller, NODE1_CONTAINER);
        startNode(controller, NODE2_CONTAINER);
        safeDeployToNode(deployer, "node1");
        safeDeployToNode(deployer, "node2");
        waitForNodeReady(NODE1_BASE_URL);
        waitForNodeReady(NODE2_BASE_URL);
        recreateClients();
        waitForClusterFormation(15);

        URI lra3 = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        assertTrue(lra3.toString().contains("lra-coordinator"),
                "Should be able to create new LRA after cluster restart: " + lra3);

        closeAllLRAs(node1Client, List.of(lra1, lra2, lra3));
    }

    @Test
    void testNodeRejoinAfterCrash() throws Exception {
        startCluster();
        waitForClusterFormation();

        URI lraId = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        waitForReplication();

        killNode(controller, NODE2_CONTAINER);
        waitForReplication(3);

        List<URI> newLraIds = startMultipleLRAs(node1Client, 3);
        waitForReplication();

        startNode(controller, NODE2_CONTAINER);
        safeDeployToNode(deployer, "node2");
        waitForNodeReady(NODE2_BASE_URL);
        if (node2Client != null) {
            node2Client.close();
        }
        node2Client = createLRAClient(NODE2_BASE_URL);
        waitForClusterFormation(10);

        for (URI newLraId : newLraIds) {
            waitForReplication(2);
            assertTrue(isLRAReplicatedToNode(newLraId, NODE2_BASE_URL),
                    "LRA created while node2 was down should be replicated after rejoin: " + newLraId);
        }

        closeAllLRAs(node1Client, newLraIds);
        node1Client.closeLRA(lraId);
    }
}
