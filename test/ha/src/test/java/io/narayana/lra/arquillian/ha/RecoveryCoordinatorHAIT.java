/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.ha;

import static io.narayana.lra.arquillian.ha.TestHelpers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.narayana.lra.client.NarayanaLRAClient;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import org.jboss.arquillian.container.test.api.ContainerController;
import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for RecoveryCoordinator in HA mode.
 *
 * Verifies that recovery coordinator operations (getCompensator,
 * replaceCompensator) work correctly across cluster nodes:
 * - A participant enlisted on Node A can be looked up from Node B
 * - A participant updated on Node B is visible from Node A
 *
 * These tests exercise the distributed store fallback path where
 * the local in-memory state does not contain the LRA.
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RecoveryCoordinatorHAIT {

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

    /**
     * Tests that a participant enlisted on Node 1 can be looked up
     * via the recovery coordinator on Node 2.
     *
     * Flow:
     * 1. Node 1 starts an LRA
     * 2. Node 1 enlists a participant (join), gets a recovery URL
     * 3. GET the recovery URL on Node 2 -> should return the participant URL
     */
    @Test
    void testGetCompensatorFromDifferentNode() throws Exception {
        // Start LRA on Node 1
        URI lraId = node1Client.startLRA(null, "recovery-ha-test", 0L, ChronoUnit.SECONDS);
        assertTrue(lraId != null && lraId.toString().contains("lra-coordinator"),
                "startLRA should return a valid LRA ID");

        try {
            // Join a participant on Node 1
            URI compensateUri = URI.create("http://example.com/participant/compensate");
            URI completeUri = URI.create("http://example.com/participant/complete");

            URI recoveryUrl = node1Client.joinLRA(lraId, 0L,
                    compensateUri, completeUri, null, null, null, null, (String) null);
            assertTrue(recoveryUrl != null && recoveryUrl.toString().contains("recovery"),
                    "joinLRA should return a valid recovery URL");

            waitForReplication();

            // GET the recovery URL targeting Node 2 instead of Node 1
            URI node2RecoveryUrl = retargetUrlToNode(recoveryUrl, NODE2_BASE_URL);

            try (Client httpClient = ClientBuilder.newClient()) {
                Response response = httpClient.target(node2RecoveryUrl)
                        .request(MediaType.APPLICATION_JSON)
                        .get();

                assertEquals(200, response.getStatus(),
                        "Node 2 should find the participant via the distributed store. "
                                + "Recovery URL: " + node2RecoveryUrl);

                String participantUrl = response.readEntity(String.class);
                assertTrue(participantUrl.contains("example.com/participant/compensate"),
                        "Response should contain the original compensator URL, but got: " + participantUrl);
            }
        } finally {
            node1Client.closeLRA(lraId);
        }
    }

    /**
     * Tests that when Node 2 updates a participant via replaceCompensator,
     * a subsequent getCompensator on Node 1 returns the updated URL.
     *
     * Flow:
     * 1. Node 1 starts an LRA and enlists a participant
     * 2. Node 2 PUTs a new compensator URL to the recovery URL
     * 3. Node 1 GETs the recovery URL -> should see the updated URL
     */
    @Test
    void testReplaceCompensatorVisibleAcrossNodes() throws Exception {
        URI lraId = node1Client.startLRA(null, "recovery-ha-test", 0L, ChronoUnit.SECONDS);
        assertTrue(lraId != null && lraId.toString().contains("lra-coordinator"),
                "startLRA should return a valid LRA ID");

        try {
            // Join a participant on Node 1
            URI compensateUri = URI.create("http://example.com/participant/compensate");
            URI completeUri = URI.create("http://example.com/participant/complete");

            URI recoveryUrl = node1Client.joinLRA(lraId, 0L,
                    compensateUri, completeUri, null, null, null, null, (String) null);
            assertTrue(recoveryUrl != null && recoveryUrl.toString().contains("recovery"),
                    "joinLRA should return a valid recovery URL");

            waitForReplication();

            String newCompensatorLink = "<http://new-host:9090/updated/compensate>;rel=\"compensate\","
                    + "<http://new-host:9090/updated/complete>;rel=\"complete\"";

            // PUT the new compensator link via Node 2
            URI node2RecoveryUrl = retargetUrlToNode(recoveryUrl, NODE2_BASE_URL);

            try (Client httpClient = ClientBuilder.newClient()) {
                Response putResponse = httpClient.target(node2RecoveryUrl)
                        .request(MediaType.APPLICATION_JSON)
                        .put(Entity.text(newCompensatorLink));

                assertEquals(200, putResponse.getStatus(),
                        "Node 2 should successfully update the participant. "
                                + "Recovery URL: " + node2RecoveryUrl);
            }

            waitForReplication();

            // GET from Node 1 — should return the updated compensator URL
            try (Client httpClient = ClientBuilder.newClient()) {
                Response getResponse = httpClient.target(recoveryUrl)
                        .request(MediaType.APPLICATION_JSON)
                        .get();

                assertEquals(200, getResponse.getStatus(),
                        "Node 1 should still find the participant after update on Node 2");

                String returnedUrl = getResponse.readEntity(String.class);
                assertEquals(newCompensatorLink, returnedUrl,
                        "Node 1 should return the exact compensator link set by Node 2");
            }
        } finally {
            node1Client.closeLRA(lraId);
        }
    }

    /**
     * Tests compensator retrieval and update after the originating node crashes.
     *
     * Flow:
     * 1. Node 1 starts an LRA and enlists a participant
     * 2. Node 1 crashes
     * 3. Node 2 GETs the compensator via the recovery URL -> should succeed
     * 4. Node 2 PUTs a new compensator URL via the recovery URL -> should succeed
     * 5. Node 1 restarts and rejoins the cluster
     * 6. Node 1 GETs the compensator -> should return the updated URL
     */
    @Test
    void testCompensatorSurvivesNodeCrashAndUpdate() throws Exception {
        // Node 1 starts an LRA and enlists a participant
        URI lraId = node1Client.startLRA(null, "recovery-ha-crash-test", 0L, ChronoUnit.SECONDS);
        assertTrue(lraId != null && lraId.toString().contains("lra-coordinator"),
                "startLRA should return a valid LRA ID");

        URI compensateUri = URI.create("http://example.com/participant/compensate");
        URI completeUri = URI.create("http://example.com/participant/complete");

        URI recoveryUrl = node1Client.joinLRA(lraId, 0L,
                compensateUri, completeUri, null, null, null, null, (String) null);
        assertNotNull(recoveryUrl, "Join should return a recovery URL");

        waitForReplication();

        // Verify the LRA is replicated before crashing Node 1
        assertTrue(isLRAReplicatedToNode(lraId, NODE2_BASE_URL),
                "LRA should be replicated to Node 2 before crash");

        // Kill Node 1 (simulates crash)
        killNode(controller, NODE1_CONTAINER);
        waitForReplication(5);

        // Node 2 retrieves the compensator via the recovery URL
        URI node2RecoveryUrl = retargetUrlToNode(recoveryUrl, NODE2_BASE_URL);

        try (Client httpClient = ClientBuilder.newClient()) {
            Response getResponse = httpClient.target(node2RecoveryUrl)
                    .request(MediaType.APPLICATION_JSON)
                    .get();

            assertEquals(200, getResponse.getStatus(),
                    "Node 2 should find the participant after Node 1 crash. "
                            + "Recovery URL: " + node2RecoveryUrl);
        }

        // Node 2 updates the compensator URL
        String updatedCompensatorLink = "<http://failover-host:9090/updated/compensate>;rel=\"compensate\","
                + "<http://failover-host:9090/updated/complete>;rel=\"complete\"";

        try (Client httpClient = ClientBuilder.newClient()) {
            Response putResponse = httpClient.target(node2RecoveryUrl)
                    .request(MediaType.APPLICATION_JSON)
                    .put(Entity.text(updatedCompensatorLink));

            assertEquals(200, putResponse.getStatus(),
                    "Node 2 should successfully update the participant after Node 1 crash");
        }

        // Restart Node 1 and wait for it to rejoin the cluster
        startNode(controller, NODE1_CONTAINER);
        safeDeployToNode(deployer, "node1");
        waitForNodeReady(NODE1_BASE_URL);
        node1Client.close();
        node1Client = createLRAClient(NODE1_BASE_URL);
        waitForClusterFormation(10);

        // Node 1 GETs the compensator — should return the updated URL, not the original
        try (Client httpClient = ClientBuilder.newClient()) {
            Response getResponse = httpClient.target(recoveryUrl)
                    .request(MediaType.APPLICATION_JSON)
                    .get();

            assertEquals(200, getResponse.getStatus(),
                    "Node 1 should find the participant after restart");

            String returnedUrl = getResponse.readEntity(String.class);
            assertEquals(updatedCompensatorLink, returnedUrl,
                    "Node 1 should return the exact compensator link set by Node 2 while Node 1 was down");
        }

        // Clean up — close the LRA from whichever node is available
        try {
            node1Client.closeLRA(lraId);
        } catch (Exception e) {
            node2Client.closeLRA(lraId);
        }
    }

    /**
     * Retargets a recovery URL from its original node to a different node.
     * Replaces the host:port portion while preserving the path.
     *
     * Example: http://node1:8080/lra-coordinator/recovery/uid/pid
     * -> http://localhost:8180/lra-coordinator/recovery/uid/pid
     */
    private static URI retargetUrlToNode(URI originalUrl, String targetNodeBaseUrl) {
        // The recovery URL path starts from /lra-coordinator/...
        // The targetNodeBaseUrl is like http://localhost:8180
        // The deployment context is /lra-coordinator
        String path = originalUrl.getPath();
        String query = originalUrl.getQuery();
        String targetUrl = targetNodeBaseUrl + path;
        if (query != null) {
            targetUrl += "?" + query;
        }
        return URI.create(targetUrl);
    }
}
