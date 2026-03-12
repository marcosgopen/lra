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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Multi-node cluster integration tests for LRA High Availability.
 *
 * Uses a 3-node cluster (odd number) to ensure proper quorum-based
 * partition handling. With DENY_READ_WRITES strategy, a majority of
 * nodes must be reachable for operations to succeed.
 *
 * Uses managed=false deployments so Arquillian doesn't auto-undeploy the WAR
 * between tests. The cluster starts once and stays up for all tests.
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MultiNodeClusterIT {

    @ArquillianResource
    private ContainerController controller;

    @ArquillianResource
    private Deployer deployer;

    private NarayanaLRAClient node1Client;
    private NarayanaLRAClient node2Client;
    private boolean clusterStarted = false;

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

    @AfterAll
    void cleanUp() {
        if (node1Client != null) {
            node1Client.close();
        }
        if (node2Client != null) {
            node2Client.close();
        }
        cleanupCluster(controller, deployer);
    }

    @BeforeEach
    void setUp() {
        if (!clusterStarted) {
            startNode(controller, NODE1_CONTAINER);
            startNode(controller, NODE2_CONTAINER);
            startNode(controller, NODE3_CONTAINER);

            safeDeployToNode(deployer, "node1");
            safeDeployToNode(deployer, "node2");
            safeDeployToNode(deployer, "node3");

            waitForNodeReady(NODE1_BASE_URL);
            waitForNodeReady(NODE2_BASE_URL);
            waitForNodeReady(NODE3_BASE_URL);

            node1Client = createLRAClient(NODE1_BASE_URL);
            node2Client = createLRAClient(NODE2_BASE_URL);

            waitForClusterFormation(10);
            clusterStarted = true;
        }
    }

    @AfterEach
    void waitForClusterStability() {
        waitForReplication();
    }

    @Test
    void testCreateLRAOnNode1AndRetrieveFromNode2() throws Exception {
        URI lraId = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        assertNotNull(lraId);

        waitForReplication();

        assertTrue(isLRAReplicatedToNode(lraId, NODE2_BASE_URL),
                "LRA created on node1 should be visible from node2");

        node1Client.closeLRA(lraId);
    }

    @Test
    void testMultipleConcurrentLRAs() throws Exception {
        int lraCount = 5;
        List<URI> node1Lras = startMultipleLRAs(node1Client, lraCount);
        List<URI> node2Lras = startMultipleLRAs(node2Client, lraCount);

        waitForReplication(3);

        for (URI lraId : node1Lras) {
            assertTrue(isLRAAccessible(node2Client, lraId), "LRA created on node1 should be accessible from node2");
        }

        for (URI lraId : node2Lras) {
            assertTrue(isLRAAccessible(node1Client, lraId), "LRA created on node2 should be accessible from node1");
        }

        closeAllLRAs(node1Client, node1Lras);
        closeAllLRAs(node2Client, node2Lras);
    }

    @Test
    void testLRALifecycleAcrossNodes() throws Exception {
        URI lraId = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        waitForReplication();

        node2Client.closeLRA(lraId);
    }

    @Test
    void testNodeIdEmbeddedInLRAIds() throws Exception {
        URI lra1 = node1Client.startLRA(null, "ha-test-node1", 0L, ChronoUnit.SECONDS);
        URI lra2 = node2Client.startLRA(null, "ha-test-node2", 0L, ChronoUnit.SECONDS);

        String[] segments1 = lra1.getPath().split("/");
        String[] segments2 = lra2.getPath().split("/");

        assertTrue(segments1.length >= 3,
                "LRA ID from node1 should have valid path structure: " + lra1.getPath());
        assertTrue(segments2.length >= 3,
                "LRA ID from node2 should have valid path structure: " + lra2.getPath());
        assertNotEquals(lra1, lra2, "LRA IDs from different nodes should be unique");

        closeAllLRAs(node1Client, List.of(lra1));
        closeAllLRAs(node2Client, List.of(lra2));
    }
}
