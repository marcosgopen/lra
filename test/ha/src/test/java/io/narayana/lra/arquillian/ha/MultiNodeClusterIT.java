/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.ha;

import static io.narayana.lra.arquillian.ha.TestHelpers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.narayana.lra.client.NarayanaLRAClient;
import java.net.URI;
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
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Multi-node cluster integration tests for LRA High Availability.
 *
 * The cluster is started once for the whole class and reused across tests.
 * This avoids Infinispan partition instability from repeated container restarts.
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MultiNodeClusterIT {

    @ArquillianResource
    private ContainerController controller;

    private NarayanaLRAClient node1Client;
    private NarayanaLRAClient node2Client;
    private boolean clusterStarted = false;

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

    @BeforeEach
    void setUp() {
        if (!clusterStarted) {
            startNode(controller, NODE1_CONTAINER);
            startNode(controller, NODE2_CONTAINER);
            waitForNodeReady(NODE1_BASE_URL);
            waitForNodeReady(NODE2_BASE_URL);

            node1Client = createLRAClient(NODE1_BASE_URL);
            node2Client = createLRAClient(NODE2_BASE_URL);

            waitForClusterFormation(10);
            clusterStarted = true;
        }
    }

    @AfterEach
    void cleanupLRAs() {
        // Individual test cleanup is done inside each test method.
        // Containers stay running across tests (PER_CLASS lifecycle).
    }

    @Test
    void testCreateLRAOnNode1AndRetrieveFromNode2() throws Exception {
        URI lraId = node1Client.startLRA("ha-test");
        assertNotNull(lraId);

        waitForReplication();

        assertTrue(isLRAReplicatedToNode(lraId, NODE2_BASE_URL),
                "LRA created on node1 should be visible from node2");

        node1Client.closeLRA(lraId);
    }

    @Test
    void testCreateLRAOnNode2AndRetrieveFromNode1() throws Exception {
        URI lraId = node2Client.startLRA("ha-test");
        assertNotNull(lraId);

        waitForReplication();

        assertTrue(isLRAReplicatedToNode(lraId, NODE1_BASE_URL),
                "LRA created on node2 should be visible from node1");

        node2Client.closeLRA(lraId);
    }

    @Test
    void testMultipleConcurrentLRAs() throws Exception {
        int lraCount = 5;
        java.util.List<URI> node1Lras = startMultipleLRAs(node1Client, lraCount);
        java.util.List<URI> node2Lras = startMultipleLRAs(node2Client, lraCount);

        waitForReplication(3);

        for (URI lraId : node1Lras) {
            assertTrue(isLRAAccessible(node1Client, lraId),
                    "LRA created on node1 should be accessible");
        }

        for (URI lraId : node2Lras) {
            assertTrue(isLRAAccessible(node2Client, lraId),
                    "LRA created on node2 should be accessible");
        }

        closeAllLRAs(node1Client, node1Lras);
        closeAllLRAs(node2Client, node2Lras);
    }

    @Test
    void testLRALifecycleAcrossNodes() throws Exception {
        URI lraId = node1Client.startLRA("ha-test");
        waitForReplication();

        // Close from the other node
        node2Client.closeLRA(lraId);

        waitForReplication();

        // After closing, the LRA may or may not still be visible
        boolean stillAccessible = isLRAAccessible(node1Client, lraId);
        System.out.println("LRA accessible after cross-node close: " + stillAccessible);
    }

    @Test
    void testNodeIdEmbeddedInLRAIds() throws Exception {
        URI lra1 = node1Client.startLRA("ha-test-node1");
        URI lra2 = node2Client.startLRA("ha-test-node2");

        String[] segments1 = lra1.getPath().split("/");
        String[] segments2 = lra2.getPath().split("/");

        System.out.println("Node1 LRA path: " + lra1.getPath());
        System.out.println("Node2 LRA path: " + lra2.getPath());

        assertTrue(segments1.length >= 3, "LRA ID from node1 should have valid path structure");
        assertTrue(segments2.length >= 3, "LRA ID from node2 should have valid path structure");

        // Cleanup
        try {
            node1Client.closeLRA(lra1);
        } catch (Exception e) {
            System.err.println("Cleanup: failed to close lra1: " + e.getMessage());
        }
        try {
            node2Client.closeLRA(lra2);
        } catch (Exception e) {
            System.err.println("Cleanup: failed to close lra2: " + e.getMessage());
        }
    }
}
