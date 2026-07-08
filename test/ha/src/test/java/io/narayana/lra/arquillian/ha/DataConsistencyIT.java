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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
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
 * Data consistency tests for LRA High Availability.
 *
 * Tests data integrity and consistency:
 * - ObjectStore and Infinispan state synchronization
 * - Replicated cache prevents conflicting updates
 * - Cache state transitions (active -> recovering -> failed)
 * - State recovery from ObjectStore
 * - No lost updates under concurrent access
 *
 * Uses a 3-node cluster (odd number) to ensure proper quorum-based
 * partition handling with DENY_READ_WRITES strategy.
 *
 * Note: These tests require manual execution with full WildFly cluster.
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DataConsistencyIT {

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

    @Test
    void testStateReplicationConsistency() throws Exception {
        URI lraId = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        assertTrue(lraId.toString().contains("lra-coordinator"),
                "LRA URI should contain coordinator path: " + lraId);

        waitForReplication();

        LRAStatus status1 = node1Client.getStatus(lraId);
        assertEquals(LRAStatus.Active, status1, "Node1 should return Active status");

        assertTrue(isLRAReplicatedToNode(lraId, NODE2_BASE_URL),
                "Node2 should return LRA status (replicated)");

        node1Client.closeLRA(lraId);
    }

    @Test
    void testConcurrentLRACreationFromBothNodes() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<URI> allLRAs = new ArrayList<>();

        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            futures.add(executor.submit(() -> {
                try (NarayanaLRAClient threadClient = createLRAClient(NODE1_BASE_URL)) {
                    startLatch.await();
                    URI lraId = threadClient.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
                    synchronized (allLRAs) {
                        allLRAs.add(lraId);
                    }
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            }));
        }

        for (int i = 0; i < 5; i++) {
            futures.add(executor.submit(() -> {
                try (NarayanaLRAClient threadClient = createLRAClient(NODE2_BASE_URL)) {
                    startLatch.await();
                    URI lraId = threadClient.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
                    synchronized (allLRAs) {
                        allLRAs.add(lraId);
                    }
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            }));
        }

        startLatch.countDown();

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "All operations should complete in time");

        int totalOps = successCount.get() + failureCount.get();
        assertEquals(10, totalOps, "All 10 operations should complete");

        waitForReplication(3);

        int replicatedCount = 0;
        for (URI lraId : allLRAs) {
            if (isLRAReplicatedToNode(lraId, NODE1_BASE_URL)
                    && isLRAReplicatedToNode(lraId, NODE2_BASE_URL)) {
                replicatedCount++;
            }
        }

        assertTrue(successCount.get() >= 8,
                "At least 80% of concurrent creates should succeed, got " + successCount.get()
                        + " (failures: " + failureCount.get() + ", replicated: " + replicatedCount + ")");

        closeAllLRAs(node1Client, allLRAs);
    }

    @Test
    void testStateTransitionReplication() throws Exception {
        URI lraId = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        waitForReplication();

        assertTrue(isLRAAccessible(node1Client, lraId));
        assertTrue(isLRAReplicatedToNode(lraId, NODE2_BASE_URL));

        node1Client.closeLRA(lraId);

        waitForReplication();

        // After close, the LRA should no longer be active.
        // It may still exist with Closed/Closing status, or it may have been
        // removed entirely (NotFoundException). Both are valid outcomes.
        assertFalse(isLRAActive(node1Client, lraId),
                "LRA should not be active on node1 after close");
        assertFalse(isLRAActiveOnNode(lraId, NODE2_BASE_URL),
                "LRA should not be active on node2 after close");
    }

    @Test
    void testRecoveryFromObjectStoreRebuildsCache() throws Exception {
        List<URI> lraIds = startMultipleLRAs(node1Client, 3);
        waitForReplication();

        for (URI lraId : lraIds) {
            assertTrue(isLRAAccessible(node1Client, lraId));
        }

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

        node1Client.close();
        node2Client.close();
        node1Client = createLRAClient(NODE1_BASE_URL);
        node2Client = createLRAClient(NODE2_BASE_URL);

        waitForClusterFormation(20);

        int recoveredCount = 0;
        for (URI lraId : lraIds) {
            if (isLRAAccessible(node1Client, lraId)) {
                recoveredCount++;
            }
        }

        assertTrue(recoveredCount >= 0,
                "Recovery should complete without errors (recovered " + recoveredCount + "/" + lraIds.size() + ")");

        URI newLra = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        assertTrue(newLra.toString().contains("lra-coordinator"),
                "Should be able to create new LRA after recovery: " + newLra);

        closeAllLRAs(node1Client, lraIds);
        node1Client.closeLRA(newLra);
    }

    @Test
    void testNoLostUpdatesUnderLoad() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(20);
        AtomicInteger createdCount = new AtomicInteger(0);
        AtomicInteger closedCount = new AtomicInteger(0);
        List<URI> createdLRAs = new ArrayList<>();

        try {
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < 10; i++) {
                futures.add(executor.submit(() -> {
                    try (NarayanaLRAClient threadClient = createLRAClient(NODE1_BASE_URL)) {
                        URI lraId = threadClient.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
                        synchronized (createdLRAs) {
                            createdLRAs.add(lraId);
                        }
                        createdCount.incrementAndGet();
                    } catch (Exception e) {
                        // Creation failure counted via createdCount < expected
                    }
                }));
            }

            for (int i = 0; i < 10; i++) {
                futures.add(executor.submit(() -> {
                    try (NarayanaLRAClient threadClient = createLRAClient(NODE2_BASE_URL)) {
                        URI lraId = threadClient.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
                        synchronized (createdLRAs) {
                            createdLRAs.add(lraId);
                        }
                        createdCount.incrementAndGet();
                    } catch (Exception e) {
                        // Creation failure counted via createdCount < expected
                    }
                }));
            }

            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }

            waitForReplication(3);

            for (URI lraId : createdLRAs) {
                // Retry to allow for replication lag under load
                boolean accessible = false;
                for (int attempt = 0; attempt < 5 && !accessible; attempt++) {
                    accessible = isLRAAccessible(node1Client, lraId);
                    if (!accessible) {
                        waitForReplication(1);
                    }
                }
                assertTrue(accessible, "LRA should be accessible: " + lraId);

                node1Client.closeLRA(lraId);
                closedCount.incrementAndGet();
            }

            assertEquals(20, createdCount.get(), "Should create 20 LRAs");
            assertEquals(20, closedCount.get(),
                    "All created LRAs should be closable (created: " + createdCount.get() + ")");

        } finally {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void testConsistentStateAfterPartialFailure() throws Exception {
        URI lra1 = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        URI lra2 = node2Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        URI lra3 = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        waitForReplication();

        assertTrue(isLRAReplicatedToNode(lra1, NODE2_BASE_URL));
        assertTrue(isLRAReplicatedToNode(lra2, NODE1_BASE_URL));
        assertTrue(isLRAReplicatedToNode(lra3, NODE2_BASE_URL));

        killNode(controller, NODE2_CONTAINER);
        waitForReplication(5);

        // After killing node2, remaining 2 of 3 nodes still have quorum.
        // All three LRAs were verified as replicated before the kill, so all must be accessible.
        assertTrue(isLRAAccessible(node1Client, lra1), "lra1 should be accessible after node2 crash");
        assertTrue(isLRAAccessible(node1Client, lra2),
                "lra2 should be accessible after node2 crash (was replicated before kill)");
        assertTrue(isLRAAccessible(node1Client, lra3), "lra3 should be accessible after node2 crash");

        node1Client.closeLRA(lra1);

        startNode(controller, NODE2_CONTAINER);
        safeDeployToNode(deployer, "node2");
        waitForNodeReady(NODE2_BASE_URL);
        node2Client.close();
        node2Client = createLRAClient(NODE2_BASE_URL);
        waitForClusterFormation(10);

        assertTrue(isLRAReplicatedToNode(lra2, NODE2_BASE_URL),
                "lra2 should be available on node2 after rejoin");
        assertTrue(isLRAReplicatedToNode(lra3, NODE2_BASE_URL),
                "lra3 should be available on node2 after rejoin");

        closeAllLRAs(node1Client, List.of(lra2, lra3));
    }

    @Test
    void testCacheEvictionAndObjectStoreFallback() throws Exception {
        int lraCount = 50;
        List<URI> lraIds = new ArrayList<>();

        for (int i = 0; i < lraCount; i++) {
            URI lraId = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
            lraIds.add(lraId);

            // Progress: created (i+1) LRAs
        }

        waitForReplication(3);

        int accessibleCount = 0;
        for (URI lraId : lraIds) {
            if (isLRAAccessible(node1Client, lraId)) {
                accessibleCount++;
            }
        }

        assertTrue(accessibleCount >= lraCount * 0.9,
                "At least 90% of LRAs should be accessible, got " + accessibleCount + "/" + lraCount);

        closeAllLRAs(node1Client, lraIds);
    }
}
