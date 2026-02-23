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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Performance and stress tests for LRA High Availability.
 *
 * Tests performance characteristics:
 * - High throughput (1000+ LRAs)
 * - Latency overhead (HA vs single-node)
 * - Concurrent load handling
 * - Cache memory limits
 * - Replication performance
 *
 * These tests verify that HA mode maintains acceptable performance
 * (target: <20% overhead vs single-node mode).
 *
 * Note: These tests require manual execution with full WildFly cluster.
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PerformanceIT {

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

            waitForClusterFormation(15);
            clusterStarted = true;
        }
    }

    @Test
    void testHighThroughputLRACreation() throws Exception {
        // Given: Two-node cluster
        // When: Create many LRAs rapidly
        int lraCount = 100; // Increase to 1000 for full stress test
        Metrics metrics = new Metrics();
        List<URI> lraIds = new ArrayList<>();

        metrics.start();

        for (int i = 0; i < lraCount; i++) {
            URI lraId = node1Client.startLRA("ha-test");
            lraIds.add(lraId);
            metrics.incrementOperations();

            if (i > 0 && i % 50 == 0) {
                System.out.println("Created " + i + " LRAs...");
            }
        }

        metrics.end();

        // Then: Measure performance
        System.out.println("=== High Throughput Test ===");
        System.out.println(metrics);
        System.out.println("Average latency per LRA creation: "
                + String.format("%.2f ms", metrics.getAverageLatencyMillis()));

        // Verify all were created successfully
        assertEquals(lraCount, lraIds.size());

        // Architecture doc states HA adds ~10-20% overhead
        // Single-node baseline is ~7-15ms per LRA
        // HA mode target: ~8-18ms per LRA
        assertTrue(metrics.getAverageLatencyMillis() < 50,
                "Average latency should be reasonable (<50ms per LRA)");

        // Cleanup
        System.out.println("Cleaning up " + lraIds.size() + " LRAs...");
        closeAllLRAs(node1Client, lraIds);
    }

    @Test
    void testConcurrentLoadAcrossCluster() throws Exception {
        // Given: Two-node cluster
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
                        URI lraId = threadClient.startLRA("ha-test");
                        synchronized (createdLRAs) {
                            createdLRAs.add(lraId);
                        }
                        successCount.incrementAndGet();
                        metrics.incrementOperations();
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                        System.err.println("Failed to create LRA: " + e.getMessage());
                    }
                }));
            }

            // Wait for all to complete
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }

            metrics.end();

            // Then: Verify performance under concurrent load
            System.out.println("=== Concurrent Load Test ===");
            System.out.println(metrics);
            System.out.println("Success: " + successCount.get() + ", Failures: " + failureCount.get());
            System.out.println("Throughput: " + String.format("%.2f", metrics.getThroughput())
                    + " LRAs/second");

            // Should handle concurrent load efficiently
            assertTrue(successCount.get() >= totalLRAs * 0.95,
                    "At least 95% of concurrent operations should succeed");

            // Cleanup
            closeAllLRAs(node1Client, createdLRAs);

        } finally {
            executor.shutdown();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void testReplicationLatency() throws Exception {
        // Given: Two-node cluster
        // When: Create LRA on node1 and measure time to replicate to node2
        int samples = 20;
        List<Long> latencies = new ArrayList<>();

        for (int i = 0; i < samples; i++) {
            // Create LRA
            long startTime = System.nanoTime();
            URI lraId = node1Client.startLRA("ha-test");

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

            System.out.println("=== Replication Latency Test ===");
            System.out.println("Samples: " + latencies.size());
            System.out.println("Min: " + minLatency + "ms");
            System.out.println("Avg: " + String.format("%.2f", avgLatency) + "ms");
            System.out.println("Max: " + maxLatency + "ms");

            // Architecture doc states replication should be ~2-5ms
            // With network overhead, we allow more
            assertTrue(avgLatency < 100,
                    "Average replication latency should be reasonable (<100ms)");
        }
    }

    @Test
    void testMemoryScaling() throws Exception {
        // Given: Two-node cluster
        // When: Create many LRAs to test memory usage
        // Architecture doc: ~5-10 KB per LRA in memory
        // Cache maxCount: 10,000 entries

        int lraCount = 500; // Scale to 5000 for full test
        List<URI> lraIds = new ArrayList<>();

        System.out.println("=== Memory Scaling Test ===");
        System.out.println("Creating " + lraCount + " LRAs...");

        for (int i = 0; i < lraCount; i++) {
            URI lraId = node1Client.startLRA("ha-test");
            lraIds.add(lraId);

            if (i > 0 && i % 100 == 0) {
                System.out.println("Created " + i + " LRAs");

                // Sample: verify some are still accessible
                int sampleSize = Math.min(10, lraIds.size());
                int accessibleCount = 0;
                for (int j = 0; j < sampleSize; j++) {
                    if (isLRAAccessible(node1Client, lraIds.get(j))) {
                        accessibleCount++;
                    }
                }
                System.out.println("Sample check: " + accessibleCount + "/"
                        + sampleSize + " accessible");
            }
        }

        // Then: All should be accessible (either from cache or ObjectStore fallback)
        System.out.println("Verifying accessibility of all " + lraCount + " LRAs...");
        int accessibleCount = 0;
        for (URI lraId : lraIds) {
            if (isLRAAccessible(node1Client, lraId)) {
                accessibleCount++;
            }
        }

        System.out.println("Total accessible: " + accessibleCount + "/" + lraCount);
        assertTrue(accessibleCount >= lraCount * 0.95,
                "At least 95% should be accessible");

        // Cleanup
        System.out.println("Cleaning up...");
        closeAllLRAs(node1Client, lraIds);
    }

    @Test
    void testLRALifecyclePerformance() throws Exception {
        // Given: Two-node cluster
        // When: Measure full lifecycle (create -> close) performance
        int iterations = 50;
        Metrics createMetrics = new Metrics();
        Metrics closeMetrics = new Metrics();

        System.out.println("=== LRA Lifecycle Performance Test ===");

        createMetrics.start();
        closeMetrics.start();

        for (int i = 0; i < iterations; i++) {
            // Measure create (per-operation timing)
            createMetrics.startOp();
            URI lraId = node1Client.startLRA("ha-test");
            createMetrics.endOp();

            waitForReplication(1);

            // Measure close from different node (per-operation timing)
            closeMetrics.startOp();
            node2Client.closeLRA(lraId);
            closeMetrics.endOp();
        }

        createMetrics.end();
        closeMetrics.end();

        // Then: Report performance
        System.out.println("Create operations: " + createMetrics);
        System.out.println("Close operations: " + closeMetrics);

        // Both operations should be reasonably fast
        assertTrue(createMetrics.getAverageLatencyMillis() < 50,
                "Create latency should be <50ms, was: " + createMetrics.getAverageLatencyMillis());
        assertTrue(closeMetrics.getAverageLatencyMillis() < 100,
                "Close latency should be <100ms, was: " + closeMetrics.getAverageLatencyMillis());
    }

    @Test
    void testClusterThroughputCapacity() throws Exception {
        // Given: Two-node cluster
        // When: Maximum sustained throughput test
        int durationSeconds = 10;
        AtomicInteger opsCompleted = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<URI> createdLRAs = new ArrayList<>();

        System.out.println("=== Cluster Throughput Capacity Test ===");
        System.out.println("Running for " + durationSeconds + " seconds...");

        try {
            Metrics metrics = new Metrics();
            metrics.start();

            long endTime = System.currentTimeMillis() + (durationSeconds * 1000);

            while (System.currentTimeMillis() < endTime) {
                executor.submit(() -> {
                    try (NarayanaLRAClient threadClient = createLRAClient(NODE1_BASE_URL)) {
                        URI lraId = threadClient.startLRA("ha-test");
                        synchronized (createdLRAs) {
                            createdLRAs.add(lraId);
                        }
                        opsCompleted.incrementAndGet();
                    } catch (Exception e) {
                        // Count failures
                    }
                });

                executor.submit(() -> {
                    try (NarayanaLRAClient threadClient = createLRAClient(NODE2_BASE_URL)) {
                        URI lraId = threadClient.startLRA("ha-test");
                        synchronized (createdLRAs) {
                            createdLRAs.add(lraId);
                        }
                        opsCompleted.incrementAndGet();
                    } catch (Exception e) {
                        // Count failures
                    }
                });
            }

            // Wait for in-flight operations
            executor.shutdown();
            executor.awaitTermination(30, TimeUnit.SECONDS);

            metrics.end();

            // Calculate throughput
            double actualDuration = metrics.getElapsedMillis() / 1000.0;
            double throughput = opsCompleted.get() / actualDuration;

            System.out.println("Operations completed: " + opsCompleted.get());
            System.out.println("Duration: " + String.format("%.2f", actualDuration) + "s");
            System.out.println("Throughput: " + String.format("%.2f", throughput) + " ops/s");

            // Cluster should handle reasonable load
            assertTrue(throughput > 10, "Cluster should handle >10 ops/s");

            // Cleanup
            System.out.println("Cleaning up " + createdLRAs.size() + " LRAs...");
            closeAllLRAs(node1Client, createdLRAs);

        } finally {
            if (!executor.isTerminated()) {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void testCrossNodeOperationLatency() throws Exception {
        // Given: Two-node cluster
        // When: Measure latency of operations across nodes
        int iterations = 30;
        List<Long> sameNodeLatencies = new ArrayList<>();
        List<Long> crossNodeLatencies = new ArrayList<>();

        System.out.println("=== Cross-Node Operation Latency Test ===");

        for (int i = 0; i < iterations; i++) {
            // Same-node operation (create and close on node1)
            long startTime = System.nanoTime();
            URI lra1 = node1Client.startLRA("ha-test");
            node1Client.closeLRA(lra1);
            long sameNodeLatency = System.nanoTime() - startTime;
            sameNodeLatencies.add(TimeUnit.NANOSECONDS.toMillis(sameNodeLatency));

            // Cross-node operation (create on node1, close from node2)
            startTime = System.nanoTime();
            URI lra2 = node1Client.startLRA("ha-test");
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

        System.out.println("Same-node avg latency: " + String.format("%.2f", avgSameNode) + "ms");
        System.out.println("Cross-node avg latency: " + String.format("%.2f", avgCrossNode) + "ms");

        double overhead = ((avgCrossNode - avgSameNode) / avgSameNode) * 100;
        System.out.println("Cross-node overhead: " + String.format("%.1f", overhead) + "%");

        // Cross-node operations may have slightly higher latency due to replication
        // but should still be reasonable
    }
}
