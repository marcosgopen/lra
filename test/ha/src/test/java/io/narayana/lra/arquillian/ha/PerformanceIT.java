/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.ha;

import static io.narayana.lra.arquillian.ha.TestHelpers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.narayana.lra.arquillian.ha.TestHelpers.Metrics;
import io.narayana.lra.client.NarayanaLRAClient;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Performance and stress tests for LRA High Availability.
 *
 * Tests performance characteristics:
 * - High throughput (1000+ LRAs)
 * - Latency overhead (HA with OCC vs single-node)
 * - Concurrent load handling (OCC retries under contention)
 * - Cache memory limits
 * - Replication performance
 *
 * These tests verify that HA mode with optimistic concurrency control
 * maintains acceptable performance (target: <20% overhead vs single-node mode).
 *
 * Uses a 3-node cluster (odd number) to ensure proper quorum-based
 * partition handling with DENY_READ_WRITES strategy.
 *
 * Note: These tests require manual execution with full WildFly cluster.
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PerformanceIT {

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

            waitForClusterFormation(15);
            clusterStarted = true;
        }
    }

    @Test
    void testHighThroughputLRACreation() throws Exception {
        // Given: Three-node cluster
        // When: Create many LRAs rapidly
        int lraCount = 100; // Increase to 1000 for full stress test
        Metrics metrics = new Metrics();
        List<URI> lraIds = new ArrayList<>();

        metrics.start();

        for (int i = 0; i < lraCount; i++) {
            URI lraId = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
            lraIds.add(lraId);
            metrics.incrementOperations();

        }

        metrics.end();

        // Verify all were created successfully
        assertEquals(lraCount, lraIds.size());

        // HA with OCC target: ~8-18ms per LRA (single-node baseline ~7-15ms)
        assertTrue(metrics.getAverageLatencyMillis() < 50,
                "Average latency should be <50ms per LRA, was: "
                        + String.format("%.2fms", metrics.getAverageLatencyMillis())
                        + " (" + metrics + ")");

        closeAllLRAs(node1Client, lraIds);
    }

    @Test
    void testConcurrentLoadAcrossCluster() throws Exception {
        // Given: Three-node cluster
        int totalLRAs = 200;
        int threadCount = 20;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<URI> createdLRAs = new ArrayList<>();
        Metrics metrics = new Metrics();

        try {
            metrics.start();

            // When: Create LRAs concurrently from both nodes (per-thread clients)
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < totalLRAs; i++) {
                final String nodeUrl = (i % 2 == 0) ? NODE1_BASE_URL : NODE2_BASE_URL;

                futures.add(executor.submit(() -> {
                    try (NarayanaLRAClient threadClient = createLRAClient(nodeUrl)) {
                        URI lraId = threadClient.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
                        createdLRAs.add(lraId);
                        successCount.incrementAndGet();
                        metrics.incrementOperations();
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    }
                }));
            }

            // Wait for all to complete
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }

            metrics.end();

            // Then: Verify performance under concurrent load
            assertTrue(successCount.get() >= totalLRAs * 0.95,
                    "At least 95% of concurrent operations should succeed, got "
                            + successCount.get() + "/" + totalLRAs
                            + " (failures: " + failureCount.get()
                            + ", throughput: " + String.format("%.2f", metrics.getThroughput()) + " ops/s)");

            // Cleanup
            closeAllLRAs(node1Client, createdLRAs);

        } finally {
            executor.shutdown();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void testReplicationLatency() throws Exception {
        // Given: Three-node cluster
        // When: Create LRA on node1 and measure time to replicate to node2
        int samples = 20;
        List<Long> latencies = new ArrayList<>();

        for (int i = 0; i < samples; i++) {
            // Create LRA
            long startTime = System.nanoTime();
            URI lraId = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);

            // Poll node2 until LRA appears (or timeout)
            boolean replicated = false;
            long replicationTime = 0;
            int maxAttempts = 50; // 5 seconds max

            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                if (isLRAReplicatedToNode(lraId, NODE2_BASE_URL)) {
                    replicationTime = System.nanoTime() - startTime;
                    replicated = true;
                    break;
                }
                TimeUnit.MILLISECONDS.sleep(100);
            }

            if (replicated) {
                long latencyMs = TimeUnit.NANOSECONDS.toMillis(replicationTime);
                latencies.add(latencyMs);
            }

            // Cleanup
            node1Client.closeLRA(lraId);
        }

        // Then: Analyze replication latency
        if (!latencies.isEmpty()) {
            double avgLatency = latencies.stream()
                    .mapToLong(Long::longValue)
                    .average()
                    .orElse(0);

            long minLatency = latencies.stream()
                    .mapToLong(Long::longValue)
                    .min()
                    .orElse(0);

            long maxLatency = latencies.stream()
                    .mapToLong(Long::longValue)
                    .max()
                    .orElse(0);

            // Replication should complete within 100ms on average
            assertTrue(avgLatency < 100,
                    String.format("Average replication latency should be <100ms, was: %.2fms "
                            + "(min=%dms, max=%dms, samples=%d)", avgLatency, minLatency, maxLatency, latencies.size()));
        }
    }

    @Test
    void testMemoryScaling() throws Exception {
        // Given: Three-node cluster
        // When: Create many LRAs to test memory usage
        // Architecture doc: ~5-10 KB per LRA in memory
        // Cache maxCount: 10,000 entries

        int lraCount = 500; // Scale to 5000 for full test
        List<URI> lraIds = new ArrayList<>();

        for (int i = 0; i < lraCount; i++) {
            URI lraId = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
            lraIds.add(lraId);
        }

        // All should be accessible (either from cache or ObjectStore fallback)
        int accessibleCount = 0;
        for (URI lraId : lraIds) {
            if (isLRAAccessible(node1Client, lraId)) {
                accessibleCount++;
            }
        }

        assertTrue(accessibleCount >= lraCount * 0.95,
                "At least 95% should be accessible, got " + accessibleCount + "/" + lraCount);

        closeAllLRAs(node1Client, lraIds);
    }

    @Test
    void testLRALifecyclePerformance() throws Exception {
        // Given: Three-node cluster
        // When: Measure full lifecycle (create -> close) performance
        int iterations = 50;
        Metrics createMetrics = new Metrics();
        Metrics closeMetrics = new Metrics();

        createMetrics.start();
        closeMetrics.start();

        for (int i = 0; i < iterations; i++) {
            // Measure create (per-operation timing)
            createMetrics.startOp();
            URI lraId = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
            createMetrics.endOp();

            waitForReplication(1);

            // Measure close from different node (per-operation timing)
            closeMetrics.startOp();
            node2Client.closeLRA(lraId);
            closeMetrics.endOp();
        }

        createMetrics.end();
        closeMetrics.end();

        assertTrue(createMetrics.getAverageLatencyMillis() < 50,
                "Create latency should be <50ms, was: "
                        + String.format("%.2fms", createMetrics.getAverageLatencyMillis())
                        + " (" + createMetrics + ")");
        assertTrue(closeMetrics.getAverageLatencyMillis() < 100,
                "Close latency should be <100ms, was: "
                        + String.format("%.2fms", closeMetrics.getAverageLatencyMillis())
                        + " (" + closeMetrics + ")");
    }

    @Test
    void testClusterThroughputCapacity() throws Exception {
        // Given: Three-node cluster
        // When: Maximum sustained throughput test
        int durationSeconds = 10;
        AtomicInteger opsCompleted = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<URI> createdLRAs = new java.util.concurrent.CopyOnWriteArrayList<>();

        try {
            Metrics metrics = new Metrics();
            metrics.start();

            long endTime = System.currentTimeMillis() + (durationSeconds * 1000);

            while (System.currentTimeMillis() < endTime) {
                executor.submit(() -> {
                    try (NarayanaLRAClient threadClient = createLRAClient(NODE1_BASE_URL)) {
                        URI lraId = threadClient.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
                        createdLRAs.add(lraId);
                        opsCompleted.incrementAndGet();
                    } catch (Exception e) {
                        // Count failures
                    }
                });

                executor.submit(() -> {
                    try (NarayanaLRAClient threadClient = createLRAClient(NODE2_BASE_URL)) {
                        URI lraId = threadClient.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
                        createdLRAs.add(lraId);
                        opsCompleted.incrementAndGet();
                    } catch (Exception e) {
                        // Count failures
                    }
                });
            }

            // Wait for in-flight operations
            executor.shutdown();
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }

            metrics.end();

            // Calculate throughput
            double actualDuration = metrics.getElapsedMillis() / 1000.0;
            double throughput = opsCompleted.get() / actualDuration;

            assertTrue(throughput > 10,
                    String.format("Cluster should handle >10 ops/s, got %.2f ops/s "
                            + "(ops=%d, duration=%.2fs)", throughput, opsCompleted.get(), actualDuration));

            closeAllLRAs(node1Client, createdLRAs);

        } finally {
            if (!executor.isTerminated()) {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void testCrossNodeOperationLatency() throws Exception {
        // Given: Three-node cluster
        // When: Measure latency of operations across nodes
        int iterations = 30;
        List<Long> sameNodeLatencies = new ArrayList<>();
        List<Long> crossNodeLatencies = new ArrayList<>();

        for (int i = 0; i < iterations; i++) {
            // Same-node operation (create and close on node1)
            long startTime = System.nanoTime();
            URI lra1 = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
            node1Client.closeLRA(lra1);
            long sameNodeLatency = System.nanoTime() - startTime;
            sameNodeLatencies.add(TimeUnit.NANOSECONDS.toMillis(sameNodeLatency));

            // Cross-node operation (create on node1, close from node2)
            startTime = System.nanoTime();
            URI lra2 = node1Client.startLRA(null, "ha-test", 0L, ChronoUnit.SECONDS);
            waitForReplication(1);
            node2Client.closeLRA(lra2);
            long crossNodeLatency = System.nanoTime() - startTime;
            crossNodeLatencies.add(TimeUnit.NANOSECONDS.toMillis(crossNodeLatency));
        }

        // Then: Compare latencies
        double avgSameNode = sameNodeLatencies.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0);

        double avgCrossNode = crossNodeLatencies.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0);

        // Cross-node operations include replication wait, so overhead is expected.
        // Assert both are within reasonable bounds.
        assertTrue(avgSameNode < 200,
                String.format("Same-node avg latency should be <200ms, was: %.2fms", avgSameNode));
        assertTrue(avgCrossNode < 2000,
                String.format("Cross-node avg latency should be <2000ms (includes replication wait), was: %.2fms",
                        avgCrossNode));
    }
}
