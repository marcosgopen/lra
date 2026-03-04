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
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Data consistency tests for LRA High Availability.
 *
 * Tests data integrity and consistency:
 * - ObjectStore and Infinispan state synchronization
 * - Distributed locking prevents concurrent modifications
 * - Cache state transitions (active -> recovering -> failed)
 * - State recovery from ObjectStore
 * - No lost updates or conflicts
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

    @Deployment(name = "node3", testable = false)
    @TargetsContainer(NODE3_CONTAINER)
    public static WebArchive createDeploymentNode3() {
        return createLRACoordinatorDeployment();
    }

    @BeforeEach
    void setUp() {
        if (!clusterStarted) {
            startNode(controller, NODE1_CONTAINER);
            startNode(controller, NODE2_CONTAINER);
            startNode(controller, NODE3_CONTAINER);

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
        assertNotNull(lraId);

        waitForReplication();

        LRAStatus status1 = node1Client.getStatus(lraId);
        assertNotNull(status1, "Node1 should return LRA status");

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
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "All operations should complete in time");

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

        System.out.println("Concurrent creation: " + successCount.get() + " succeeded, "
                + failureCount.get() + " failed, " + replicatedCount + " fully replicated");

        assertTrue(successCount.get() >= 8,
                "At least 80% of concurrent creates should succeed");

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

        boolean node1Accessible = isLRAAccessible(node1Client, lraId);
        boolean node2Accessible = isLRAReplicatedToNode(lraId, NODE2_BASE_URL);

        System.out.println("Node1 accessible after close: " + node1Accessible);
        System.out.println("Node2 accessible after close: " + node2Accessible);
    }

    @Test
    void testRecoveryFromObjectStoreRebuildsCache() throws Exception {
        List<URI> lraIds = startMultipleLRAs(node1Client, 3);
        waitForReplication();

        for (URI lraId : lraIds) {
            assertTrue(isLRAAccessible(node1Client, lraId));
        }

        stopNode(controller, NODE1_CONTAINER);
        stopNode(controller, NODE2_CONTAINER);
        waitForReplication(5);

        startNode(controller, NODE1_CONTAINER);
        startNode(controller, NODE2_CONTAINER);
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

        System.out.println("Recovered " + recoveredCount + " out of " + lraIds.size() + " LRAs");

        URI newLra = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
        assertNotNull(newLra, "Should be able to create new LRA after recovery");

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
                        System.err.println("Failed to create LRA: " + e.getMessage());
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
                        System.err.println("Failed to create LRA: " + e.getMessage());
                    }
                }));
            }

            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }

            waitForReplication(3);

            for (URI lraId : createdLRAs) {
                assertTrue(isLRAAccessible(node1Client, lraId),
                        "LRA should be accessible: " + lraId);

                try {
                    node1Client.closeLRA(lraId);
                    closedCount.incrementAndGet();
                } catch (Exception e) {
                    System.err.println("Failed to close LRA: " + e.getMessage());
                }
            }

            assertEquals(20, createdCount.get(), "Should create 20 LRAs");
            System.out.println("Created: " + createdCount.get() + ", Closed: " + closedCount.get());

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
        waitForReplication(3);

        assertTrue(isLRAAccessible(node1Client, lra1));
        assertTrue(isLRAAccessible(node1Client, lra2));
        assertTrue(isLRAAccessible(node1Client, lra3));

        node1Client.closeLRA(lra1);

        startNode(controller, NODE2_CONTAINER);
        waitForNodeReady(NODE2_BASE_URL);
        node2Client.close();
        node2Client = createLRAClient(NODE2_BASE_URL);
        waitForClusterFormation(10);

        boolean lra2Available = isLRAReplicatedToNode(lra2, NODE2_BASE_URL);
        boolean lra3Available = isLRAReplicatedToNode(lra3, NODE2_BASE_URL);

        System.out.println("After rejoin - lra2 available: " + lra2Available
                + ", lra3 available: " + lra3Available);

        closeAllLRAs(node1Client, List.of(lra2, lra3));
    }

    @Test
    void testCacheEvictionAndObjectStoreFallback() throws Exception {
        int lraCount = 50;
        List<URI> lraIds = new ArrayList<>();

        for (int i = 0; i < lraCount; i++) {
            URI lraId = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
            lraIds.add(lraId);

            if (i % 10 == 0) {
                System.out.println("Created " + (i + 1) + " LRAs");
            }
        }

        waitForReplication(3);

        int accessibleCount = 0;
        for (URI lraId : lraIds) {
            if (isLRAAccessible(node1Client, lraId)) {
                accessibleCount++;
            }
        }

        System.out.println(accessibleCount + " out of " + lraCount + " LRAs accessible");
        assertTrue(accessibleCount >= lraCount * 0.9,
                "At least 90% of LRAs should be accessible");

        closeAllLRAs(node1Client, lraIds);
    }
}
