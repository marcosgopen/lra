/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.ha;

import io.narayana.lra.client.NarayanaLRAClient;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.jboss.arquillian.container.test.api.ContainerController;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;

/**
 * Test utilities for HA integration tests.
 * Provides helpers for:
 * - Node lifecycle management
 * - Cluster formation waiting
 * - LRA operations via NarayanaLRAClient
 * - Metrics collection
 */
public class TestHelpers {

    public static final String NODE1_CONTAINER = "wildfly-ha-node1";
    public static final String NODE2_CONTAINER = "wildfly-ha-node2";
    public static final String NODE1_BASE_URL = "http://localhost:8080";
    public static final String NODE2_BASE_URL = "http://localhost:8180";
    public static final String LRA_COORDINATOR_PATH = "/lra-coordinator/lra-coordinator";

    /**
     * Creates a NarayanaLRAClient pointing at the coordinator on the given node.
     */
    public static NarayanaLRAClient createLRAClient(String nodeBaseUrl) {
        return new NarayanaLRAClient(nodeBaseUrl + LRA_COORDINATOR_PATH);
    }

    /**
     * Wait for a node's coordinator to become ready by polling getAllLRAs().
     */
    public static void waitForNodeReady(String nodeBaseUrl) {
        waitForNodeReady(nodeBaseUrl, 30);
    }

    /**
     * Wait for a node's coordinator to become ready with custom attempt count.
     */
    public static void waitForNodeReady(String nodeBaseUrl, int maxAttempts) {
        int attempt = 0;
        while (attempt < maxAttempts) {
            try (NarayanaLRAClient probe = createLRAClient(nodeBaseUrl)) {
                probe.getAllLRAs();
                System.out.println("Node ready: " + nodeBaseUrl);
                return;
            } catch (Exception e) {
                System.out.println("");
                // Node not ready yet
            }
            attempt++;
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for node", e);
            }
        }
        throw new RuntimeException("Node failed to become ready: " + nodeBaseUrl);
    }

    /**
     * Wait for cluster to form.
     */
    public static void waitForClusterFormation() {
        waitForClusterFormation(10);
    }

    /**
     * Wait for cluster formation with custom timeout.
     */
    public static void waitForClusterFormation(int timeoutSeconds) {
        System.out.println("Waiting for cluster formation...");
        try {
            TimeUnit.SECONDS.sleep(timeoutSeconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for cluster", e);
        }
        System.out.println("Cluster formation complete");
    }

    /**
     * Wait for state replication between nodes.
     */
    public static void waitForReplication() {
        waitForReplication(2);
    }

    /**
     * Wait for replication with custom timeout.
     */
    public static void waitForReplication(int seconds) {
        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for replication", e);
        }
    }

    /**
     * Start multiple LRAs on a node.
     */
    public static List<URI> startMultipleLRAs(NarayanaLRAClient lraClient, int count) {
        List<URI> lraIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            lraIds.add(lraClient.startLRA("ha-test-" + i));
        }
        return lraIds;
    }

    /**
     * Close all LRAs in the list (best effort).
     */
    public static void closeAllLRAs(NarayanaLRAClient lraClient, List<URI> lraIds) {
        for (URI lraId : lraIds) {
            try {
                lraClient.closeLRA(lraId);
            } catch (Exception e) {
                System.err.println("Failed to close LRA: " + lraId + " - " + e.getMessage());
            }
        }
    }

    /**
     * Check whether an LRA is accessible via the given client.
     */
    public static boolean isLRAAccessible(NarayanaLRAClient lraClient, URI lraId) {
        try {
            LRAStatus status = lraClient.getStatus(lraId);
            return status != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check whether an LRA created on one node is visible from another node.
     */
    public static boolean isLRAReplicatedToNode(URI lraId, String targetNodeBaseUrl) {
        try (NarayanaLRAClient targetClient = createLRAClient(targetNodeBaseUrl)) {
            LRAStatus status = targetClient.getStatus(lraId);
            return status != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- Deployment helpers ----

    /**
     * Creates a self-contained LRA coordinator WAR deployment with all required
     * dependencies resolved via Maven. Based on WildflyLRACoordinatorDeployment
     * from the arquillian-extension module, with additional WildFly module
     * dependencies for Infinispan (HA clustering) and SmallRye Stork.
     */
    public static WebArchive createLRACoordinatorDeployment() {
        String lraVersion = System.getProperty("version.microprofile.lra");
        String projectVersion = System.getProperty("project.version");

        // WildFly module dependencies:
        // - org.jgroups: JGroups transport for Infinispan clustering (optional dep of infinispan.core)
        // - org.infinispan.core: Infinispan cache APIs
        // - org.infinispan.lock: clustered locks for distributed locking
        // - org.infinispan.commons: Infinispan common utilities
        // - io.smallrye.stork: required by NarayanaLRAClient
        String manifestMF = "Dependencies: org.jboss.jandex,"
                + " org.jboss.jts export services,"
                + " org.jboss.logging,"
                + " org.jgroups,"
                + " org.infinispan.core export,"
                + " org.infinispan.lock,"
                + " org.infinispan.commons,"
                + " io.smallrye.stork\n";

        return ShrinkWrap.create(WebArchive.class, "lra-coordinator.war")
                .addAsLibraries(Maven.resolver()
                        .resolve("org.eclipse.microprofile.lra:microprofile-lra-api:" + lraVersion)
                        .withoutTransitivity().asFile())
                .addAsLibraries(Maven.configureResolver()
                        .workOffline()
                        .withMavenCentralRepo(false)
                        .withClassPathResolution(true)
                        .resolve("org.jboss.narayana.lra:lra-coordinator-jar:" + projectVersion,
                                "org.jboss.narayana.lra:lra-proxy-api:" + projectVersion,
                                "org.jboss.narayana.lra:narayana-lra:" + projectVersion,
                                "org.jboss.narayana.lra:lra-client:" + projectVersion,
                                "org.jboss.narayana.lra:lra-service-base:" + projectVersion)
                        .withoutTransitivity().asFile())
                .addAsManifestResource(new StringAsset(manifestMF), "MANIFEST.MF")
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
    }

    // ---- Container lifecycle helpers ----

    /**
     * Start a container node.
     */
    public static void startNode(ContainerController controller, String containerName) {
        System.out.println("Starting container: " + containerName);
        controller.start(containerName);
    }

    /**
     * Stop a container node gracefully.
     */
    public static void stopNode(ContainerController controller, String containerName) {
        System.out.println("Stopping container: " + containerName);
        try {
            controller.stop(containerName);
        } catch (Exception e) {
            System.err.println("Error stopping container " + containerName + ": " + e.getMessage());
        }
    }

    /**
     * Kill a container node abruptly (simulates crash).
     * Falls back to stop() if kill() is not supported.
     */
    public static void killNode(ContainerController controller, String containerName) {
        System.out.println("Killing container: " + containerName);
        try {
            controller.kill(containerName);
        } catch (UnsupportedOperationException e) {
            System.err.println("Kill not supported, falling back to stop for " + containerName);
            stopNode(controller, containerName);
        } catch (Exception e) {
            System.err.println("Error killing container " + containerName + ": " + e.getMessage());
        }
    }

    /**
     * Stop a container node, ignoring errors if already stopped.
     */
    public static void stopNodeQuietly(ContainerController controller, String containerName) {
        try {
            controller.stop(containerName);
        } catch (Exception e) {
            // Already stopped or not running
        }
    }

    // ---- Performance metrics ----

    /**
     * Performance metrics holder that accumulates timing across multiple
     * start/end pairs. Supports two usage patterns:
     *
     * 1. Single measurement: start() ... end() with incrementOperations()
     * 2. Per-operation: startOp() ... endOp() called in a loop (auto-increments)
     */
    public static class Metrics {
        private long overallStartTime;
        private long overallEndTime;
        private long accumulatedNanos;
        private long currentOpStart;
        private int operationCount;

        public void start() {
            this.overallStartTime = System.nanoTime();
        }

        public void end() {
            this.overallEndTime = System.nanoTime();
        }

        public void startOp() {
            this.currentOpStart = System.nanoTime();
        }

        public void endOp() {
            this.accumulatedNanos += System.nanoTime() - currentOpStart;
            this.operationCount++;
        }

        public void incrementOperations() {
            this.operationCount++;
        }

        public long getElapsedMillis() {
            return TimeUnit.NANOSECONDS.toMillis(overallEndTime - overallStartTime);
        }

        public double getAverageLatencyMillis() {
            if (operationCount == 0) {
                return 0;
            }
            long totalNanos = accumulatedNanos > 0 ? accumulatedNanos : (overallEndTime - overallStartTime);
            return TimeUnit.NANOSECONDS.toMillis(totalNanos) / (double) operationCount;
        }

        public double getThroughput() {
            long elapsedMs = getElapsedMillis();
            if (elapsedMs == 0) {
                return operationCount;
            }
            return operationCount / (elapsedMs / 1000.0);
        }

        public int getOperationCount() {
            return operationCount;
        }

        @Override
        public String toString() {
            return String.format(
                    "Metrics{operations=%d, elapsed=%dms, avgLatency=%.2fms, throughput=%.2f ops/s}",
                    operationCount, getElapsedMillis(), getAverageLatencyMillis(), getThroughput());
        }
    }
}
