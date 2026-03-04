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
import java.util.Collections;
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
 * OCC (Optimistic Concurrency Control) integration tests for LRA High Availability.
 *
 * Verifies that the versioned CAS-based write strategy provides correct behavior
 * under concurrent cross-node access:
 * - No lost updates when multiple nodes race to modify the same LRA
 * - Consistent state after concurrent close/cancel races
 * - Proper behavior during node failures with concurrent operations
 *
 * Uses a 3-node cluster with one-time startup (PER_CLASS lifecycle).
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class OccConsistencyIT {

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
    void testConcurrentCloseFromBothNodes() throws Exception {
        URI lraId = node1Client.startLRA(null, "occ-close-test", 0L, ChronoUnit.SECONDS);
        waitForReplication();

        assertTrue(isLRAReplicatedToNode(lraId, NODE2_BASE_URL),
                "LRA must be replicated to node2 before concurrent close");

        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = executor.submit(() -> {
                try (NarayanaLRAClient client = createLRAClient(NODE1_BASE_URL)) {
                    startLatch.await();
                    client.closeLRA(lraId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            });
            Future<?> f2 = executor.submit(() -> {
                try (NarayanaLRAClient client = createLRAClient(NODE2_BASE_URL)) {
                    startLatch.await();
                    client.closeLRA(lraId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            });

            startLatch.countDown();
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertTrue(successCount.get() >= 1,
                "At least one concurrent close should succeed (successes: " + successCount.get()
                        + ", failures: " + failureCount.get() + ")");

        waitForReplication();

        assertFalse(isLRAAccessible(node1Client, lraId),
                "LRA should not be active on node1 after concurrent close");
        assertFalse(isLRAReplicatedToNode(lraId, NODE2_BASE_URL),
                "LRA should not be active on node2 after concurrent close");
    }

    @Test
    void testConcurrentCancelFromBothNodes() throws Exception {
        URI lraId = node1Client.startLRA(null, "occ-cancel-test", 0L, ChronoUnit.SECONDS);
        waitForReplication();

        assertTrue(isLRAReplicatedToNode(lraId, NODE2_BASE_URL),
                "LRA must be replicated to node2 before concurrent cancel");

        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = executor.submit(() -> {
                try (NarayanaLRAClient client = createLRAClient(NODE1_BASE_URL)) {
                    startLatch.await();
                    client.cancelLRA(lraId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            });
            Future<?> f2 = executor.submit(() -> {
                try (NarayanaLRAClient client = createLRAClient(NODE2_BASE_URL)) {
                    startLatch.await();
                    client.cancelLRA(lraId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            });

            startLatch.countDown();
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertTrue(successCount.get() >= 1,
                "At least one concurrent cancel should succeed (successes: " + successCount.get()
                        + ", failures: " + failureCount.get() + ")");

        waitForReplication();

        assertFalse(isLRAAccessible(node1Client, lraId),
                "LRA should not be active on node1 after concurrent cancel");
        assertFalse(isLRAReplicatedToNode(lraId, NODE2_BASE_URL),
                "LRA should not be active on node2 after concurrent cancel");
    }

    @Test
    void testNoLostUpdatesUnderConcurrentCreation() throws Exception {
        int threadsPerNode = 10;
        int totalExpected = threadsPerNode * 2;
        List<URI> allLRAs = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(totalExpected);
        try {
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < threadsPerNode; i++) {
                futures.add(executor.submit(() -> {
                    try (NarayanaLRAClient client = createLRAClient(NODE1_BASE_URL)) {
                        startLatch.await();
                        URI lraId = client.startLRA(null, "occ-create-test", 0L, ChronoUnit.SECONDS);
                        allLRAs.add(lraId);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        // counted via successCount < expected
                    }
                }));
            }
            for (int i = 0; i < threadsPerNode; i++) {
                futures.add(executor.submit(() -> {
                    try (NarayanaLRAClient client = createLRAClient(NODE2_BASE_URL)) {
                        startLatch.await();
                        URI lraId = client.startLRA(null, "occ-create-test", 0L, ChronoUnit.SECONDS);
                        allLRAs.add(lraId);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        // counted via successCount < expected
                    }
                }));
            }

            startLatch.countDown();

            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertEquals(totalExpected, successCount.get(),
                "All " + totalExpected + " concurrent creates should succeed");
        assertEquals(totalExpected, allLRAs.size(),
                "All " + totalExpected + " LRA URIs should be collected");

        // Verify uniqueness
        long uniqueCount = allLRAs.stream().distinct().count();
        assertEquals(totalExpected, uniqueCount,
                "All LRA IDs should be unique (got " + uniqueCount + " unique out of " + totalExpected + ")");

        waitForReplication(5);

        for (URI lraId : allLRAs) {
            boolean onNode1 = false;
            boolean onNode2 = false;
            for (int attempt = 0; attempt < 3; attempt++) {
                onNode1 = isLRAAccessible(node1Client, lraId);
                onNode2 = isLRAReplicatedToNode(lraId, NODE2_BASE_URL);
                if (onNode1 && onNode2)
                    break;
                waitForReplication(1);
            }
            assertTrue(onNode1, "LRA should be visible on node1: " + lraId);
            assertTrue(onNode2, "LRA should be visible on node2: " + lraId);
        }

        closeAllLRAs(node1Client, allLRAs);
    }

    @Test
    void testCloseOnOneNodeWhileOtherNodeCreatesNewLRAs() throws Exception {
        List<URI> originalLRAs = startMultipleLRAs(node1Client, 5);
        waitForReplication(3);

        for (URI lraId : originalLRAs) {
            assertTrue(isLRAReplicatedToNode(lraId, NODE2_BASE_URL),
                    "Original LRA must be replicated before concurrent ops: " + lraId);
        }

        List<URI> newLRAs = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger closedCount = new AtomicInteger(0);
        AtomicInteger createdCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(10);
        try {
            List<Future<?>> futures = new ArrayList<>();

            // Node2 closes the original LRAs
            for (URI lraId : originalLRAs) {
                futures.add(executor.submit(() -> {
                    try (NarayanaLRAClient client = createLRAClient(NODE2_BASE_URL)) {
                        client.closeLRA(lraId);
                        closedCount.incrementAndGet();
                    } catch (Exception e) {
                        // close failure
                    }
                }));
            }

            // Node1 creates new LRAs concurrently
            for (int i = 0; i < 5; i++) {
                futures.add(executor.submit(() -> {
                    try (NarayanaLRAClient client = createLRAClient(NODE1_BASE_URL)) {
                        URI lraId = client.startLRA(null, "occ-new-test", 0L, ChronoUnit.SECONDS);
                        newLRAs.add(lraId);
                        createdCount.incrementAndGet();
                    } catch (Exception e) {
                        // create failure
                    }
                }));
            }

            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertEquals(5, closedCount.get(),
                "All 5 original LRAs should be closed");
        assertEquals(5, createdCount.get(),
                "All 5 new LRAs should be created");

        waitForReplication(3);

        for (URI lraId : originalLRAs) {
            assertFalse(isLRAAccessible(node1Client, lraId),
                    "Closed LRA should not be active: " + lraId);
        }
        for (URI lraId : newLRAs) {
            assertTrue(isLRAAccessible(node1Client, lraId),
                    "Newly created LRA should be accessible: " + lraId);
        }

        closeAllLRAs(node1Client, newLRAs);
    }

    @Test
    void testStateConsistencyAfterNodeCrashDuringConcurrentOps() throws Exception {
        List<URI> lraIds = startMultipleLRAs(node1Client, 3);
        waitForReplication(3);

        for (URI lraId : lraIds) {
            assertTrue(isLRAReplicatedToNode(lraId, NODE2_BASE_URL),
                    "LRA must be replicated before crash test: " + lraId);
        }

        // Start concurrent close operations, then kill node1 mid-flight
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (URI lraId : lraIds) {
                futures.add(executor.submit(() -> {
                    try (NarayanaLRAClient client = createLRAClient(NODE1_BASE_URL)) {
                        client.closeLRA(lraId);
                    } catch (Exception e) {
                        // Expected: some closes may fail due to crash
                    }
                }));
            }

            // Kill node1 while close operations may still be in flight
            killNode(controller, NODE1_CONTAINER);

            for (Future<?> f : futures) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // Expected: futures may fail due to killed node
                }
            }
        } finally {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }

        // After node1 crash, JGroups needs to detect failure + Infinispan rebalances
        waitForReplication(10);

        // Node2 should be able to see surviving LRAs (those whose close didn't complete)
        // and manage them — the key invariant is no corruption
        URI newLra = node2Client.startLRA(null, "occ-post-crash", 0L, ChronoUnit.SECONDS);
        assertNotNull(newLra, "Should be able to create new LRA on node2 after node1 crash");

        node2Client.closeLRA(newLra);

        // Restart node1 for subsequent tests
        startNode(controller, NODE1_CONTAINER);
        safeDeployToNode(deployer, "node1");
        waitForNodeReady(NODE1_BASE_URL);
        node1Client.close();
        node1Client = createLRAClient(NODE1_BASE_URL);
        waitForClusterFormation(10);

        // Clean up any surviving LRAs
        closeAllLRAs(node2Client, lraIds);
    }

    @Test
    void testRapidStatusTransitionsAcrossNodes() throws Exception {
        URI lraId = node1Client.startLRA(null, "occ-rapid-test", 30L, ChronoUnit.SECONDS);
        waitForReplication();

        assertTrue(isLRAReplicatedToNode(lraId, NODE2_BASE_URL),
                "LRA must be replicated before rapid operations");

        // Concurrently: node2 renews time limit while node1 reads status
        int iterations = 10;
        AtomicInteger renewSuccesses = new AtomicInteger(0);
        AtomicInteger statusSuccesses = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> renewFuture = executor.submit(() -> {
                try (NarayanaLRAClient client = createLRAClient(NODE2_BASE_URL)) {
                    for (int i = 0; i < iterations; i++) {
                        try {
                            client.renewTimeLimit(lraId, 30L);
                            renewSuccesses.incrementAndGet();
                        } catch (Exception e) {
                            // Renew may fail under contention
                        }
                    }
                }
            });

            Future<?> statusFuture = executor.submit(() -> {
                try (NarayanaLRAClient client = createLRAClient(NODE1_BASE_URL)) {
                    for (int i = 0; i < iterations; i++) {
                        try {
                            LRAStatus status = client.getStatus(lraId);
                            assertNotNull(status, "Status should never be null for an active LRA");
                            assertEquals(LRAStatus.Active, status,
                                    "Status should be Active during renewals");
                            statusSuccesses.incrementAndGet();
                        } catch (Exception e) {
                            // Status read may fail under contention
                        }
                    }
                }
            });

            renewFuture.get(30, TimeUnit.SECONDS);
            statusFuture.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertTrue(renewSuccesses.get() > 0,
                "At least some renew operations should succeed (got " + renewSuccesses.get() + "/" + iterations + ")");
        assertTrue(statusSuccesses.get() > 0,
                "At least some status reads should succeed (got " + statusSuccesses.get() + "/" + iterations + ")");

        // Close from node2 and verify closed on both nodes
        node2Client.closeLRA(lraId);
        waitForReplication();

        assertFalse(isLRAAccessible(node1Client, lraId),
                "LRA should not be active on node1 after close");
        assertFalse(isLRAReplicatedToNode(lraId, NODE2_BASE_URL),
                "LRA should not be active on node2 after close");
    }
}
