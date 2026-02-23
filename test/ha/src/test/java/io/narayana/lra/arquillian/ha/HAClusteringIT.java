/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.ha;

import static org.junit.jupiter.api.Assertions.*;

import io.narayana.lra.coordinator.domain.model.LongRunningAction;
import io.narayana.lra.coordinator.domain.service.LRAService;
import io.narayana.lra.coordinator.internal.infinispan.InfinispanClusterCoordinator;
import io.narayana.lra.coordinator.internal.infinispan.InfinispanStore;
import org.junit.jupiter.api.Test;

/**
 * Integration test for LRA High Availability clustering components.
 *
 * Tests:
 * - Component initialization
 * - HA mode detection
 * - Node ID embedding in LRA IDs
 *
 * Note: Full multi-node cluster tests require manual WildFly cluster setup
 * and are better suited for system-level integration testing.
 */
public class HAClusteringIT {

    @Test
    void testInfinispanStoreInitialization() {
        // Given: InfinispanStore instance
        InfinispanStore store = new InfinispanStore();

        // When: Initialize without caches (single-node mode)
        store.initialize();

        // Then: Should initialize without HA
        assertFalse(store.isHaEnabled(), "HA should be disabled without Infinispan caches");
    }

    @Test
    void testClusterCoordinatorInitialization() {
        // Given: InfinispanClusterCoordinator
        InfinispanClusterCoordinator coordinator = new InfinispanClusterCoordinator();

        // When/Then: Should not be initialized without cache manager
        assertFalse(coordinator.isInitialized(), "Should not be initialized without cache manager");
    }

    @Test
    void testHAModeDetection() {
        // Given: System property for HA mode
        String originalValue = System.getProperty("lra.coordinator.ha.enabled");

        try {
            // When: HA disabled
            System.clearProperty("lra.coordinator.ha.enabled");
            InfinispanStore store1 = new InfinispanStore();
            store1.initialize();

            // Then: HA should be disabled
            assertFalse(store1.isHaEnabled());

            // When: HA enabled
            System.setProperty("lra.coordinator.ha.enabled", "true");
            InfinispanStore store2 = new InfinispanStore();
            store2.initialize();

            // Then: Still disabled without caches
            assertFalse(store2.isHaEnabled(), "HA requires Infinispan caches to be available");

        } finally {
            // Restore original value
            if (originalValue != null) {
                System.setProperty("lra.coordinator.ha.enabled", originalValue);
            } else {
                System.clearProperty("lra.coordinator.ha.enabled");
            }
        }
    }

    @Test
    void testNodeIdEmbeddingInSingleInstanceMode() throws Exception {
        // Given: HA disabled
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
            service.initializeHA(new InfinispanStore() {
                @Override
                public boolean isHaEnabled() {
                    return true;
                }
            }, null, null);

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
            // Restore original values
            if (originalHaEnabled != null) {
                System.setProperty("lra.coordinator.ha.enabled", originalHaEnabled);
            } else {
                System.clearProperty("lra.coordinator.ha.enabled");
            }
            if (originalNodeId != null) {
                System.setProperty("lra.coordinator.node.id", originalNodeId);
            } else {
                System.clearProperty("lra.coordinator.node.id");
            }
        }
    }

    @Test
    void testGetNodeIdFromSystemProperty() {
        String originalValue = System.getProperty("lra.coordinator.node.id");

        try {
            // Given: Node ID set via system property
            System.setProperty("lra.coordinator.node.id", "my-coordinator-1");

            LRAService service = new LRAService();
            service.initializeHA(null, null, null);

            // When/Then: Should return the configured value
            assertEquals("my-coordinator-1", service.getNodeId());

        } finally {
            if (originalValue != null) {
                System.setProperty("lra.coordinator.node.id", originalValue);
            } else {
                System.clearProperty("lra.coordinator.node.id");
            }
        }
    }

    @Test
    void testGetNodeIdFallback() {
        String originalValue = System.getProperty("lra.coordinator.node.id");

        try {
            // Given: No system property (relies on HOSTNAME env var or fallback)
            System.clearProperty("lra.coordinator.node.id");

            LRAService service = new LRAService();
            service.initializeHA(null, null, null);

            // When/Then: Should return some value (either HOSTNAME or fallback)
            String nodeId = service.getNodeId();
            assertNotNull(nodeId);
            assertFalse(nodeId.isEmpty());

        } finally {
            if (originalValue != null) {
                System.setProperty("lra.coordinator.node.id", originalValue);
            }
        }
    }

    @Test
    void testInfinispanStoreAvailabilityWithoutCache() {
        // Given: InfinispanStore without Infinispan cache (single-instance mode)
        InfinispanStore store = new InfinispanStore();
        store.initialize();

        // When/Then: isAvailable() returns true in single-instance mode
        // because single-instance mode is always "available" (no distributed state to lose)
        assertTrue(store.isAvailable(), "Single-instance mode should always be available");
        assertFalse(store.isHaEnabled(), "HA should be disabled without caches");
    }

    @Test
    void testInfinispanStoreOperationsWhenDisabled() {
        // Given: InfinispanStore in single-node mode (HA disabled)
        InfinispanStore store = new InfinispanStore();
        store.initialize();
        assertFalse(store.isHaEnabled());

        // When/Then: save, remove, and load should be no-ops without throwing
        assertDoesNotThrow(() -> store.saveLRA(null, null));
        assertDoesNotThrow(() -> store.removeLRA(null));

        // loadLRA returns null when HA is disabled
        assertNull(store.loadLRA(null));

        // Bulk operations return empty collections
        assertTrue(store.getAllActiveLRAs().isEmpty());
        assertTrue(store.getAllRecoveringLRAs().isEmpty());
        assertTrue(store.getAllFailedLRAs().isEmpty());
    }

    @Test
    void testProviderFallbackBehavior() {
        // Given: System with HA disabled
        String originalValue = System.getProperty("lra.coordinator.ha.enabled");

        try {
            System.clearProperty("lra.coordinator.ha.enabled");

            // When: Create LRAService and initialize HA with null store
            LRAService service = new LRAService();
            service.initializeHA(null, null, null);

            // Then: Should fall back to single-instance mode
            assertFalse(service.isHaEnabled(), "Should be in single-instance mode with null store");
        } finally {
            if (originalValue != null) {
                System.setProperty("lra.coordinator.ha.enabled", originalValue);
            } else {
                System.clearProperty("lra.coordinator.ha.enabled");
            }
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
            service.initializeHA(new InfinispanStore() {
                @Override
                public boolean isHaEnabled() {
                    return true;
                }
            }, null, null);

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
            if (originalHaEnabled != null) {
                System.setProperty("lra.coordinator.ha.enabled", originalHaEnabled);
            } else {
                System.clearProperty("lra.coordinator.ha.enabled");
            }
            if (originalNodeId != null) {
                System.setProperty("lra.coordinator.node.id", originalNodeId);
            } else {
                System.clearProperty("lra.coordinator.node.id");
            }
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
                service.initializeHA(new InfinispanStore() {
                    @Override
                    public boolean isHaEnabled() {
                        return true;
                    }
                }, null, null);

                String baseUrl = "http://localhost:8080/lra-coordinator";
                LongRunningAction lra = new LongRunningAction(service, baseUrl, null, "test");

                String lraIdPath = lra.getId().getPath();
                assertTrue(lraIdPath.contains("/" + testNodeId + "/"),
                        "LRA ID should contain node ID '" + testNodeId + "': " + lraIdPath);
            }

        } finally {
            if (originalHaEnabled != null) {
                System.setProperty("lra.coordinator.ha.enabled", originalHaEnabled);
            } else {
                System.clearProperty("lra.coordinator.ha.enabled");
            }
            if (originalNodeId != null) {
                System.setProperty("lra.coordinator.node.id", originalNodeId);
            } else {
                System.clearProperty("lra.coordinator.node.id");
            }
        }
    }

    @Test
    void testHAModeToggling() {
        // Test switching between HA and single-instance mode
        String originalValue = System.getProperty("lra.coordinator.ha.enabled");

        try {
            // Start with HA disabled
            System.clearProperty("lra.coordinator.ha.enabled");
            InfinispanStore store1 = new InfinispanStore();
            store1.initialize();
            assertFalse(store1.isHaEnabled());

            // Enable HA (but without actual cache, should still be disabled)
            System.setProperty("lra.coordinator.ha.enabled", "true");
            InfinispanStore store2 = new InfinispanStore();
            store2.initialize();
            assertFalse(store2.isHaEnabled(), "HA requires actual Infinispan caches");

            // Disable again
            System.clearProperty("lra.coordinator.ha.enabled");
            InfinispanStore store3 = new InfinispanStore();
            store3.initialize();
            assertFalse(store3.isHaEnabled());

        } finally {
            if (originalValue != null) {
                System.setProperty("lra.coordinator.ha.enabled", originalValue);
            } else {
                System.clearProperty("lra.coordinator.ha.enabled");
            }
        }
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
}
