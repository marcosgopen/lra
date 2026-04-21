/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.narayana.lra.coordinator.domain.model.LongRunningAction;
import io.narayana.lra.coordinator.domain.service.LRAService;
import io.narayana.lra.coordinator.infinispan.InfinispanClusterCoordinator;
import org.junit.jupiter.api.Test;

/**
 * Component tests for LRA High Availability clustering.
 *
 * Tests:
 * - Cluster coordinator initialization and detection
 * - HA mode detection and service initialization
 * - Node ID embedding in LRA IDs (single-instance vs HA mode)
 *
 * These are fast unit-level tests that do not require WildFly or Arquillian.
 * Full multi-node cluster tests are in the *IT.java integration test classes.
 */
public class HAClusteringTest {

    @Test
    void testClusterCoordinatorInitialization() {
        // Given: InfinispanClusterCoordinator without cache manager
        InfinispanClusterCoordinator coordinator = new InfinispanClusterCoordinator();

        // When/Then: Should not be initialized without cache manager
        assertFalse(coordinator.isInitialized(), "Should not be initialized without cache manager");
    }

    @Test
    void testClusterCoordinatorWithoutCacheManager() {
        // Given: ClusterCoordinator without cache manager
        InfinispanClusterCoordinator coordinator = new InfinispanClusterCoordinator();

        // When/Then: Should not be the coordinator
        assertFalse(coordinator.isCoordinator(),
                "Should not be coordinator without cache manager");
        assertFalse(coordinator.isInitialized(),
                "Should not be initialized without cache manager");
    }

    @Test
    void testClusterCoordinatorNullCacheManagerIsNoOp() {
        InfinispanClusterCoordinator coordinator = new InfinispanClusterCoordinator();

        // When: Initialize with null
        coordinator.initialize(null);

        // Then: Still not initialized
        assertFalse(coordinator.isInitialized());
        assertFalse(coordinator.isCoordinator());
    }

    @Test
    void testNodeIdEmbeddingInSingleInstanceMode() throws Exception {
        // Given: HA disabled
        String originalValue = System.getProperty("lra.coordinator.ha.enabled");

        try {
            System.clearProperty("lra.coordinator.ha.enabled");
            LRAService service = new LRAService();
            String baseUrl = "http://localhost:8080/lra-coordinator";

            // When: Create LRA
            LongRunningAction lra = new LongRunningAction(service, baseUrl, null, "test-client");

            // Then: LRA ID does NOT contain node ID segment
            String lraIdPath = lra.getId().getPath();
            assertFalse(lraIdPath.contains("/node-"), "Single-instance mode should not embed node ID");

            // Path should have format: /lra-coordinator/{uid}
            String[] segments = lraIdPath.split("/");
            assertEquals(3, segments.length, "Expected 3 segments: empty, lra-coordinator, uid");
            assertEquals("lra-coordinator", segments[1]);
        } finally {
            restoreProperty("lra.coordinator.ha.enabled", originalValue);
        }
    }

    @Test
    void testNodeIdEmbeddingInHAMode() throws Exception {
        // Given: HA enabled with specific node ID
        String originalHaEnabled = System.getProperty("lra.coordinator.ha.enabled");
        String originalNodeId = System.getProperty("lra.coordinator.node.id");

        try {
            System.setProperty("lra.coordinator.ha.enabled", "true");
            System.setProperty("lra.coordinator.node.id", "test-node-1");

            LRAService service = new LRAService();
            service.initializeHA(null);

            String baseUrl = "http://localhost:8080/lra-coordinator";

            // When: Create LRA
            LongRunningAction lra = new LongRunningAction(service, baseUrl, null, "test-client");

            // Then: LRA ID contains node ID
            String lraIdPath = lra.getId().getPath();
            assertTrue(lraIdPath.contains("/test-node-1/"), "HA mode should embed node ID in LRA ID");

            // Path should have format: /lra-coordinator/{node-id}/{uid}
            String[] segments = lraIdPath.split("/");
            assertEquals(4, segments.length, "Expected 4 segments: empty, lra-coordinator, node-id, uid");
            assertEquals("lra-coordinator", segments[1]);
            assertEquals("test-node-1", segments[2]);
        } finally {
            restoreProperty("lra.coordinator.ha.enabled", originalHaEnabled);
            restoreProperty("lra.coordinator.node.id", originalNodeId);
        }
    }

    @Test
    void testGetNodeIdFromSystemProperty() {
        String originalValue = System.getProperty("lra.coordinator.node.id");

        try {
            // Given: Node ID set via system property
            System.setProperty("lra.coordinator.node.id", "my-coordinator-1");

            LRAService service = new LRAService();
            service.initializeHA(null);

            // When/Then: Should return the configured value
            assertEquals("my-coordinator-1", service.getNodeId());
        } finally {
            restoreProperty("lra.coordinator.node.id", originalValue);
        }
    }

    @Test
    void testGetNodeIdFallback() {
        String originalValue = System.getProperty("lra.coordinator.node.id");

        try {
            // Given: No system property (relies on Narayana node identifier or fallback)
            System.clearProperty("lra.coordinator.node.id");

            LRAService service = new LRAService();
            service.initializeHA(null);

            // When/Then: Should return some value (either Narayana node id or fallback)
            String nodeId = service.getNodeId();
            assertNotNull(nodeId);
            assertFalse(nodeId.isEmpty());
        } finally {
            restoreProperty("lra.coordinator.node.id", originalValue);
        }
    }

    @Test
    void testProviderFallbackBehavior() {
        // Given: System with HA disabled
        String originalValue = System.getProperty("lra.coordinator.ha.enabled");

        try {
            System.clearProperty("lra.coordinator.ha.enabled");

            // When: Create LRAService without calling initializeHA
            LRAService service = new LRAService();

            // Then: Should be in single-instance mode
            assertFalse(service.isHaEnabled(), "Should be in single-instance mode without initializeHA");

            // When: initializeHA is called, HA mode is enabled
            service.initializeHA(null);
            assertTrue(service.isHaEnabled(),
                    "initializeHA enables HA mode");
        } finally {
            restoreProperty("lra.coordinator.ha.enabled", originalValue);
        }
    }

    @Test
    void testNodeIdEmbeddingPathFormat() throws Exception {
        // Given: HA mode enabled
        String originalHaEnabled = System.getProperty("lra.coordinator.ha.enabled");
        String originalNodeId = System.getProperty("lra.coordinator.node.id");

        try {
            System.setProperty("lra.coordinator.ha.enabled", "true");
            System.setProperty("lra.coordinator.node.id", "ha-node-42");

            LRAService service = new LRAService();
            service.initializeHA(null);

            String baseUrl = "http://localhost:8080/lra-coordinator";

            // When: Create LRA
            LongRunningAction lra = new LongRunningAction(service, baseUrl, null, "test-client");

            // Then: Verify exact path format
            String lraIdPath = lra.getId().getPath();

            // Should contain: /lra-coordinator/ha-node-42/{uid}
            assertTrue(lraIdPath.contains("/ha-node-42/"),
                    "LRA ID should contain node ID 'ha-node-42': " + lraIdPath);
            assertTrue(lraIdPath.startsWith("/lra-coordinator/"),
                    "LRA ID should start with /lra-coordinator/: " + lraIdPath);
        } finally {
            restoreProperty("lra.coordinator.ha.enabled", originalHaEnabled);
            restoreProperty("lra.coordinator.node.id", originalNodeId);
        }
    }

    @Test
    void testMultipleNodeIdFormats() throws Exception {
        // Test that various node ID formats work correctly
        String[] testNodeIds = {
                "node-1",
                "node_2",
                "NODE-3",
                "pod-abc123",
                "127.0.0.1",
                "server.example.com"
        };

        String originalHaEnabled = System.getProperty("lra.coordinator.ha.enabled");
        String originalNodeId = System.getProperty("lra.coordinator.node.id");

        try {
            System.setProperty("lra.coordinator.ha.enabled", "true");

            for (String testNodeId : testNodeIds) {
                System.setProperty("lra.coordinator.node.id", testNodeId);

                LRAService service = new LRAService();
                service.initializeHA(null);

                String baseUrl = "http://localhost:8080/lra-coordinator";
                LongRunningAction lra = new LongRunningAction(service, baseUrl, null, "test");

                String lraIdPath = lra.getId().getPath();
                assertTrue(lraIdPath.contains("/" + testNodeId + "/"),
                        "LRA ID should contain node ID '" + testNodeId + "': " + lraIdPath);
            }
        } finally {
            restoreProperty("lra.coordinator.ha.enabled", originalHaEnabled);
            restoreProperty("lra.coordinator.node.id", originalNodeId);
        }
    }

    private static void restoreProperty(String key, String originalValue) {
        if (originalValue != null) {
            System.setProperty(key, originalValue);
        } else {
            System.clearProperty(key);
        }
    }
}
