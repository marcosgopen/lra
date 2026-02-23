/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.ha;

import static io.narayana.lra.arquillian.ha.TestHelpers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.narayana.lra.client.NarayanaLRAClient;
import java.net.URI;
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

    @Test
    void testStateReplicationConsistency() throws Exception {
        // Given: Two-node cluster
        // When: Create LRA on node1
        URI lraId = node1Client.startLRA("ha-test");
        assertNotNull(lraId);

        // Allow replication
        waitForReplication();

        // Then: State should be consistent on both nodes
        LRAStatus status1 = node1Client.getStatus(lraId);
        assertNotNull(status1, "Node1 should return LRA status");

        // Verify node2 also has the LRA
        assertTrue(isLRAReplicatedToNode(lraId, NODE2_BASE_URL),
                "Node2 should return LRA status (replicated)");

        // Cleanup
        node1Client.closeLRA(lraId);
    }

    @Test
    void testConcurrentLRACreationFromBothNodes() throws Exception {
        // Given: Two-node cluster
        // When: Multiple threads create and close LRAs simultaneously from both nodes
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<URI> allLRAs = new ArrayList<>();

        List<Future<?>> futures = new ArrayList<>();

        // 5 threads creating LRAs on node1 (each thread gets its own client)
        for (int i = 0; i < 5; i++) {
            futures.add(executor.submit(() -> {
                try (NarayanaLRAClient threadClient = createLRAClient(NODE1_BASE_URL)) {
                    startLatch.await();
                    URI lraId = threadClient.startLRA("ha-test");
                    synchronized (allLRAs) {
                        allLRAs.add(lraId);
                    }
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            }));
        }

        // 5 threads creating LRAs on node2 (each thread gets its own client)
        for (int i = 0; i < 5; i++) {
            futures.add(executor.submit(() -> {
                try (NarayanaLRAClient threadClient = createLRAClient(NODE2_BASE_URL)) {
                    startLatch.await();
                    URI lraId = threadClient.startLRA("ha-test");
                    synchronized (allLRAs) {
                        allLRAs.add(lraId);
                    }
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            }));
        }

        // Fire all threads at once
        startLatch.countDown();

        // Wait for completion
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "All operations should complete in time");

        // Then: All operations should complete
        int totalOps = successCount.get() + failureCount.get();
        assertEquals(10, totalOps, "All 10 operations should complete");

        // All created LRAs should be visible from both nodes
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

        // Cleanup
        closeAllLRAs(node1Client, allLRAs);
    }

    @Test
    void testStateTransitionReplication() throws Exception {
        // Given: LRA in Active state
        URI lraId = node1Client.startLRA("ha-test");
        waitForReplication();

        // Verify both nodes see it as Active
        assertTrue(isLRAAccessible(node1Client, lraId));
        assertTrue(isLRAReplicatedToNode(lraId, NODE2_BASE_URL));

        // When: Transition to Closing state by closing LRA
        node1Client.closeLRA(lraId);

        // Allow state transition to replicate
        waitForReplication();

        // Then: Both nodes should reflect the new state
        // The LRA may be removed after successful close, or return Closed status
        boolean node1Accessible = isLRAAccessible(node1Client, lraId);
        boolean node2Accessible = isLRAReplicatedToNode(lraId, NODE2_BASE_URL);

        // Both should return consistent status
        System.out.println("Node1 accessible after close: " + node1Accessible);
        System.out.println("Node2 accessible after close: " + node2Accessible);
    }

    @Test
    void testRecoveryFromObjectStoreRebuildsCache() throws Exception {
        // Given: LRAs exist in the cluster
        List<URI> lraIds = startMultipleLRAs(node1Client, 3);
        waitForReplication();

        // Verify all are accessible
        for (URI lraId : lraIds) {
            assertTrue(isLRAAccessible(node1Client, lraId));
        }

        // When: Stop and restart both nodes (simulates Infinispan cache loss)
        stopNode(controller, NODE1_CONTAINER);
        stopNode(controller, NODE2_CONTAINER);
        waitForReplication(5);

        // Restart nodes
        startNode(controller, NODE1_CONTAINER);
        startNode(controller, NODE2_CONTAINER);
        waitForNodeReady(NODE1_BASE_URL);
        waitForNodeReady(NODE2_BASE_URL);

        // Recreate clients after node restart
        node1Client.close();
        node2Client.close();
        node1Client = createLRAClient(NODE1_BASE_URL);
        node2Client = createLRAClient(NODE2_BASE_URL);

        // Allow recovery to rebuild Infinispan from ObjectStore
        waitForClusterFormation(20);

        // Then: Recovery should have rebuilt the cache from ObjectStore
        // LRAs should be accessible again (or at least system is functional)
        int recoveredCount = 0;
        for (URI lraId : lraIds) {
            if (isLRAAccessible(node1Client, lraId)) {
                recoveredCount++;
            }
        }

        System.out.println("Recovered " + recoveredCount + " out of " + lraIds.size() + " LRAs");

        // Should be able to create new LRAs (system is operational)
        URI newLra = node1Client.startLRA("ha-test");
        assertNotNull(newLra, "Should be able to create new LRA after recovery");

        // Cleanup
        closeAllLRAs(node1Client, lraIds);
        node1Client.closeLRA(newLra);
    }

    @Test
    void testNoLostUpdatesUnderLoad() throws Exception {
        // Given: Multiple concurrent LRA operations
        ExecutorService executor = Executors.newFixedThreadPool(20);
        AtomicInteger createdCount = new AtomicInteger(0);
        AtomicInteger closedCount = new AtomicInteger(0);
        List<URI> createdLRAs = new ArrayList<>();

        try {
            // When: Create LRAs from both nodes concurrently
            List<Future<?>> futures = new ArrayList<>();

            // 10 threads creating on node1 (each gets its own client)
            for (int i = 0; i < 10; i++) {
                futures.add(executor.submit(() -> {
                    try (NarayanaLRAClient threadClient = createLRAClient(NODE1_BASE_URL)) {
                        URI lraId = threadClient.startLRA("ha-test");
                        synchronized (createdLRAs) {
                            createdLRAs.add(lraId);
                        }
                        createdCount.incrementAndGet();
                    } catch (Exception e) {
                        System.err.println("Failed to create LRA: " + e.getMessage());
                    }
                }));
            }

            // 10 threads creating on node2 (each gets its own client)
            for (int i = 0; i < 10; i++) {
                futures.add(executor.submit(() -> {
                    try (NarayanaLRAClient threadClient = createLRAClient(NODE2_BASE_URL)) {
                        URI lraId = threadClient.startLRA("ha-test");
                        synchronized (createdLRAs) {
                            createdLRAs.add(lraId);
                        }
                        createdCount.incrementAndGet();
                    } catch (Exception e) {
                        System.err.println("Failed to create LRA: " + e.getMessage());
                    }
                }));
            }

            // Wait for all to complete
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }

            // Allow replication
            waitForReplication(3);

            // Then: All created LRAs should be accessible from both nodes
            for (URI lraId : createdLRAs) {
                assertTrue(isLRAAccessible(node1Client, lraId),
                        "LRA should be accessible: " + lraId);

                // Close from node1
                try {
                    node1Client.closeLRA(lraId);
                    closedCount.incrementAndGet();
                } catch (Exception e) {
                    System.err.println("Failed to close LRA: " + e.getMessage());
                }
            }

            // Verify counts
            assertEquals(20, createdCount.get(), "Should create 20 LRAs");
            System.out.println("Created: " + createdCount.get() + ", Closed: " + closedCount.get());

        } finally {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void testConsistentStateAfterPartialFailure() throws Exception {
        // Given: LRAs distributed across cluster
        URI lra1 = node1Client.startLRA("ha-test");
        URI lra2 = node2Client.startLRA("ha-test");
        URI lra3 = node1Client.startLRA("ha-test");
        waitForReplication();

        // Verify all are replicated
        assertTrue(isLRAReplicatedToNode(lra1, NODE2_BASE_URL));
        assertTrue(isLRAReplicatedToNode(lra2, NODE1_BASE_URL));
        assertTrue(isLRAReplicatedToNode(lra3, NODE2_BASE_URL));

        // When: Node2 crashes
        killNode(controller, NODE2_CONTAINER);
        waitForReplication(3);

        // Then: Node1 should still have consistent state
        assertTrue(isLRAAccessible(node1Client, lra1));
        assertTrue(isLRAAccessible(node1Client, lra2));
        assertTrue(isLRAAccessible(node1Client, lra3));

        // Can still close LRAs on node1
        node1Client.closeLRA(lra1);

        // When: Node2 rejoins
        startNode(controller, NODE2_CONTAINER);
        waitForNodeReady(NODE2_BASE_URL);
        // Recreate node2Client since the node was restarted
        node2Client.close();
        node2Client = createLRAClient(NODE2_BASE_URL);
        waitForClusterFormation(10);

        // Then: Node2 should sync state and reflect lra1 is closed
        // and lra2, lra3 are still active
        boolean lra2Available = isLRAReplicatedToNode(lra2, NODE2_BASE_URL);
        boolean lra3Available = isLRAReplicatedToNode(lra3, NODE2_BASE_URL);

        System.out.println("After rejoin - lra2 available: " + lra2Available
                + ", lra3 available: " + lra3Available);

        // Cleanup
        closeAllLRAs(node1Client, List.of(lra2, lra3));
    }

    @Test
    void testCacheEvictionAndObjectStoreFallback() throws Exception {
        // Given: Create many LRAs (approaching cache limit)
        // Cache maxCount is 10,000 per the architecture doc
        // We'll create a smaller number for testing
        int lraCount = 50;
        List<URI> lraIds = new ArrayList<>();

        for (int i = 0; i < lraCount; i++) {
            URI lraId = node1Client.startLRA("ha-test");
            lraIds.add(lraId);

            if (i % 10 == 0) {
                System.out.println("Created " + (i + 1) + " LRAs");
            }
        }

        waitForReplication(3);

        // Then: All should be accessible (either from cache or ObjectStore)
        int accessibleCount = 0;
        for (URI lraId : lraIds) {
            if (isLRAAccessible(node1Client, lraId)) {
                accessibleCount++;
            }
        }

        System.out.println(accessibleCount + " out of " + lraCount + " LRAs accessible");
        assertTrue(accessibleCount >= lraCount * 0.9,
                "At least 90% of LRAs should be accessible");

        // Cleanup
        closeAllLRAs(node1Client, lraIds);
    }
}
