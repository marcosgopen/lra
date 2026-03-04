/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.ha;

import io.narayana.lra.LRAConstants;
import io.narayana.lra.LRAData;
import io.narayana.lra.client.NarayanaLRAClient;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jboss.arquillian.container.test.api.ContainerController;
import org.jboss.arquillian.container.test.api.Deployer;
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

    private static final Logger LOG = Logger.getLogger(TestHelpers.class.getName());

    public static final String NODE1_CONTAINER = "wildfly-ha-node1";
    public static final String NODE2_CONTAINER = "wildfly-ha-node2";
    public static final String NODE3_CONTAINER = "wildfly-ha-node3";
    public static final String NODE1_BASE_URL = "http://localhost:8080";
    public static final String NODE2_BASE_URL = "http://localhost:8180";
    public static final String NODE3_BASE_URL = "http://localhost:8280";
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
                LOG.info("Node ready: " + nodeBaseUrl);
                return;
            } catch (Exception e) {
                // Node not ready yet, retrying
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
        LOG.info("Waiting for cluster formation...");
        try {
            TimeUnit.SECONDS.sleep(timeoutSeconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for cluster", e);
        }
        LOG.info("Cluster formation complete");
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
            lraIds.add(lraClient.startLRA(null, "ha-test-" + i, 0L, ChronoUnit.SECONDS));
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
                LOG.log(Level.WARNING, "Failed to close LRA: " + lraId, e);
            }
        }
    }

    /**
     * Check whether an LRA is accessible via the given client.
     * Uses getAllLRAs() to query the client's own coordinator (not the LRA's originating node).
     * Compares by LRA UID to handle the case where different nodes report different host/port in the URI.
     */
    public static boolean isLRAAccessible(NarayanaLRAClient lraClient, URI lraId) {
        try {
            String targetUid = LRAConstants.getLRAUid(lraId);
            List<LRAData> allLRAs = lraClient.getAllLRAs();
            return allLRAs.stream()
                    .anyMatch(data -> targetUid.equals(LRAConstants.getLRAUid(data.getLraId())));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check whether an LRA created on one node is visible from another node.
     * Uses getAllLRAs() to query the target node's coordinator directly,
     * avoiding the issue where getStatus() routes to the LRA's originating node.
     * Compares by LRA UID to handle the case where different nodes report different host/port in the URI.
     */
    public static boolean isLRAReplicatedToNode(URI lraId, String targetNodeBaseUrl) {
        try (NarayanaLRAClient targetClient = createLRAClient(targetNodeBaseUrl)) {
            String targetUid = LRAConstants.getLRAUid(lraId);
            List<LRAData> allLRAs = targetClient.getAllLRAs();
            return allLRAs.stream()
                    .anyMatch(data -> targetUid.equals(LRAConstants.getLRAUid(data.getLraId())));
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
     *
     * Includes:
     * - jboss-deployment-structure.xml to import WildFly's Infinispan modules
     * - web.xml with resource-env-ref entries that trigger WildFly to start
     * the LRA cache services on deployment
     */
    public static WebArchive createLRACoordinatorDeployment() {
        String lraVersion = System.getProperty("version.microprofile.lra");
        String projectVersion = System.getProperty("project.version");

        // jboss-deployment-structure.xml: import WildFly Infinispan and JGroups modules
        String jbossDeploymentStructure = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<jboss-deployment-structure xmlns=\"urn:jboss:deployment-structure:1.2\">\n"
                + "  <deployment>\n"
                + "    <dependencies>\n"
                + "      <module name=\"org.jboss.jandex\"/>\n"
                + "      <module name=\"org.jboss.jts\" export=\"true\" services=\"export\"/>\n"
                + "      <module name=\"org.jboss.logging\"/>\n"
                + "      <module name=\"org.jgroups\"/>\n"
                + "      <module name=\"org.infinispan\" export=\"true\" services=\"import\"/>\n"
                + "      <module name=\"org.infinispan.core\" export=\"true\" services=\"import\"/>\n"
                + "      <module name=\"org.infinispan.commons\"/>\n"
                + "      <module name=\"org.infinispan.protostream\"/>\n"
                + "      <module name=\"io.smallrye.stork\"/>\n"
                + "    </dependencies>\n"
                + "  </deployment>\n"
                + "</jboss-deployment-structure>\n";

        // web.xml: resource-env-ref entries for JNDI lookup of WildFly-managed caches.
        // These references trigger WildFly to start the lra cache container and its
        // caches when the deployment is activated.
        // web.xml: resource-env-ref entries for JNDI lookup of WildFly-managed caches.
        // Both the cache container AND each individual cache must be referenced —
        // WildFly only starts caches that are explicitly requested via JNDI.
        String webXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\"\n"
                + "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                + "         xsi:schemaLocation=\"https://jakarta.ee/xml/ns/jakartaee\n"
                + "                             https://jakarta.ee/xml/ns/jakartaee/web-app_6_0.xsd\"\n"
                + "         version=\"6.0\">\n"
                + "  <resource-env-ref>\n"
                + "    <resource-env-ref-name>infinispan/container/lra</resource-env-ref-name>\n"
                + "    <resource-env-ref-type>org.infinispan.manager.EmbeddedCacheManager</resource-env-ref-type>\n"
                + "    <lookup-name>java:jboss/infinispan/container/lra</lookup-name>\n"
                + "  </resource-env-ref>\n"
                + "  <resource-env-ref>\n"
                + "    <resource-env-ref-name>infinispan/cache/lra/lra-active</resource-env-ref-name>\n"
                + "    <resource-env-ref-type>org.infinispan.Cache</resource-env-ref-type>\n"
                + "    <lookup-name>java:jboss/infinispan/cache/lra/lra-active</lookup-name>\n"
                + "  </resource-env-ref>\n"
                + "  <resource-env-ref>\n"
                + "    <resource-env-ref-name>infinispan/cache/lra/lra-recovering</resource-env-ref-name>\n"
                + "    <resource-env-ref-type>org.infinispan.Cache</resource-env-ref-type>\n"
                + "    <lookup-name>java:jboss/infinispan/cache/lra/lra-recovering</lookup-name>\n"
                + "  </resource-env-ref>\n"
                + "  <resource-env-ref>\n"
                + "    <resource-env-ref-name>infinispan/cache/lra/lra-failed</resource-env-ref-name>\n"
                + "    <resource-env-ref-type>org.infinispan.Cache</resource-env-ref-type>\n"
                + "    <lookup-name>java:jboss/infinispan/cache/lra/lra-failed</lookup-name>\n"
                + "  </resource-env-ref>\n"
                + "</web-app>\n";

        return ShrinkWrap.create(WebArchive.class, "lra-coordinator.war")
                .addAsLibraries(Maven.resolver()
                        .resolve("org.eclipse.microprofile.lra:microprofile-lra-api:" + lraVersion)
                        .withoutTransitivity().asFile())
                .addAsLibraries(Maven.configureResolver()
                        .workOffline()
                        .withMavenCentralRepo(false)
                        .withClassPathResolution(true)
                        .resolve("org.jboss.narayana.lra:lra-coordinator-jar:" + projectVersion,
                                "org.jboss.narayana.lra:lra-coordinator-ha-infinispan:" + projectVersion,
                                "org.jboss.narayana.lra:lra-proxy-api:" + projectVersion,
                                "org.jboss.narayana.lra:narayana-lra:" + projectVersion,
                                "org.jboss.narayana.lra:lra-client:" + projectVersion,
                                "org.jboss.narayana.lra:lra-service-base:" + projectVersion)
                        .withoutTransitivity().asFile())
                .addAsWebInfResource(new StringAsset(jbossDeploymentStructure), "jboss-deployment-structure.xml")
                .addAsWebInfResource(new StringAsset(webXml), "web.xml")
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
    }

    // ---- Deployment helpers (lifecycle) ----

    /**
     * Deploy to a node, undeploying first if already deployed.
     * Handles the case where a previous test class left a deployment behind.
     */
    public static void safeDeployToNode(Deployer deployer, String deploymentName) {
        try {
            deployer.undeploy(deploymentName);
        } catch (Exception e) {
            // Nothing to undeploy — expected for first deployment
        }
        deployer.deploy(deploymentName);
    }

    /**
     * Undeploy from a node, ignoring errors if not deployed or container is down.
     */
    public static void undeployQuietly(Deployer deployer, String deploymentName) {
        try {
            deployer.undeploy(deploymentName);
        } catch (Exception e) {
            // Already undeployed or container is down
        }
    }

    /**
     * Clean up all nodes: undeploy WARs, then stop containers.
     * Call from {@code @AfterAll} to leave a clean state for the next test class.
     */
    public static void cleanupCluster(ContainerController controller, Deployer deployer) {
        // Undeploy from running containers first (order matters)
        undeployQuietly(deployer, "node1");
        undeployQuietly(deployer, "node2");
        undeployQuietly(deployer, "node3");
        // Then stop containers
        stopNodeQuietly(controller, NODE1_CONTAINER);
        stopNodeQuietly(controller, NODE2_CONTAINER);
        stopNodeQuietly(controller, NODE3_CONTAINER);
    }

    // ---- Container lifecycle helpers ----

    /**
     * Start a container node.
     */
    public static void startNode(ContainerController controller, String containerName) {
        LOG.info("Starting container: " + containerName);
        controller.start(containerName);
    }

    /**
     * Stop a container node gracefully.
     */
    public static void stopNode(ContainerController controller, String containerName) {
        LOG.info("Stopping container: " + containerName);
        try {
            controller.stop(containerName);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error stopping container " + containerName, e);
        }
    }

    /**
     * Kill a container node abruptly (simulates crash).
     * Falls back to stop() if kill() is not supported.
     */
    public static void killNode(ContainerController controller, String containerName) {
        LOG.info("Killing container: " + containerName);
        try {
            controller.kill(containerName);
        } catch (UnsupportedOperationException e) {
            LOG.warning("Kill not supported, falling back to stop for " + containerName);
            stopNode(controller, containerName);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error killing container " + containerName, e);
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
