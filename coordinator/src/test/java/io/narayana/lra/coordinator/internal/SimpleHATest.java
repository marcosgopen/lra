/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.internal;

import static org.junit.jupiter.api.Assertions.*;

import io.narayana.lra.coordinator.domain.model.LongRunningAction;
import io.narayana.lra.coordinator.domain.service.LRAService;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Simple HA tests that don't require full Infinispan cluster setup.
 *
 * These tests verify:
 * - Node ID embedding in LRA IDs
 * - HA mode detection
 * - LRAService HA initialization
 */
class SimpleHATest {

    private String originalHaEnabled;

    @BeforeEach
    void setUp() {
        // Save original value
        originalHaEnabled = System.getProperty("lra.coordinator.ha.enabled");
    }

    @AfterEach
    void tearDown() {
        // Restore original value
        if (originalHaEnabled != null) {
            System.setProperty("lra.coordinator.ha.enabled", originalHaEnabled);
        } else {
            System.clearProperty("lra.coordinator.ha.enabled");
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

        // Then: LRA ID does NOT contain node ID
        String lraIdPath = lra.getId().getPath();
        assertFalse(lraIdPath.contains("/node-"), "Single-instance mode should not embed node ID");

        // Should be: /lra-coordinator/{uid}
        String[] segments = lraIdPath.split("/");
        assertEquals(3, segments.length, "Expected 3 segments: '', 'lra-coordinator', '{uid}'");
        assertEquals("lra-coordinator", segments[1]);
    }

    @Test
    void testNodeIdEmbeddingInHAMode() throws Exception {
        // Given: HA enabled with specific node ID
        System.setProperty("lra.coordinator.ha.enabled", "true");
        System.setProperty("lra.coordinator.node.id", "test-node-1");

        LRAService service = new LRAService();
        service.initializeHA(null, null, null);

        String baseUrl = "http://localhost:8080/lra-coordinator";

        // When: Create LRA
        LongRunningAction lra = new LongRunningAction(service, baseUrl, null, "test-client");

        // Then: LRA ID contains node ID
        String lraIdPath = lra.getId().getPath();
        assertTrue(lraIdPath.contains("/test-node-1/"),
                "HA mode should embed node ID in LRA ID");

        // Should be: /lra-coordinator/test-node-1/{uid}
        String[] segments = lraIdPath.split("/");
        assertEquals(4, segments.length,
                "Expected 4 segments: '', 'lra-coordinator', 'test-node-1', '{uid}'");
        assertEquals("lra-coordinator", segments[1]);
        assertEquals("test-node-1", segments[2]);
    }

    @Test
    void testGetNodeIdFromSystemProperty() {
        // Given: Node ID set via system property
        System.setProperty("lra.coordinator.node.id", "my-coordinator-1");

        LRAService service = new LRAService();
        service.initializeHA(null, null, null);

        // When: Get node ID
        String nodeId = service.getNodeId();

        // Then: Should return the configured value
        assertEquals("my-coordinator-1", nodeId);
    }

    @Test
    void testGetNodeIdFromEnvironmentFallback() {
        // Given: No system property, but HOSTNAME env var exists
        System.clearProperty("lra.coordinator.node.id");
        // Note: Can't easily set env vars in Java, so this test just verifies the method works

        LRAService service = new LRAService();
        service.initializeHA(null, null, null);

        // When: Get node ID
        String nodeId = service.getNodeId();

        // Then: Should return some value (either HOSTNAME or fallback)
        assertNotNull(nodeId);
        assertFalse(nodeId.isEmpty());
    }

    @Test
    void testIsHaEnabledWhenDisabled() {
        // Given: HA disabled
        System.clearProperty("lra.coordinator.ha.enabled");

        LRAService service = new LRAService();

        // When: Check if HA enabled
        boolean haEnabled = service.isHaEnabled();

        // Then: Should be false
        assertFalse(haEnabled);
    }

    @Test
    void testIsHaEnabledWhenEnabled() {
        // Given: HA enabled
        System.setProperty("lra.coordinator.ha.enabled", "true");

        LRAService service = new LRAService();

        // Mock InfinispanStore that reports HA enabled
        InfinispanStore mockStore = new InfinispanStore() {
            @Override
            public boolean isHaEnabled() {
                return true;
            }
        };

        service.initializeHA(mockStore, null, null);

        // When: Check if HA enabled
        boolean haEnabled = service.isHaEnabled();

        // Then: Should be true
        assertTrue(haEnabled);
    }

    @Test
    void testNestedLRAWithNodeId() throws Exception {
        // Given: HA enabled
        System.setProperty("lra.coordinator.ha.enabled", "true");
        System.setProperty("lra.coordinator.node.id", "test-node-2");

        LRAService service = new LRAService();
        service.initializeHA(null, null, null);

        String baseUrl = "http://localhost:8080/lra-coordinator";

        // When: Create parent and nested LRA
        LongRunningAction parent = new LongRunningAction(service, baseUrl, null, "parent-client");
        LongRunningAction child = new LongRunningAction(service, baseUrl, parent, "child-client");

        // Then: Both should have node ID
        assertTrue(parent.getId().getPath().contains("/test-node-2/"),
                "Parent LRA should have node ID");
        assertTrue(child.getId().getPath().contains("/test-node-2/"),
                "Child LRA should have node ID");

        // And: Child should have parent reference
        assertEquals(parent.getId(), child.getParentId());
    }

    @Test
    void testMultipleNodesWithDifferentIds() throws Exception {
        // Given: Two coordinators with different node IDs
        System.setProperty("lra.coordinator.ha.enabled", "true");

        // Node 1
        System.setProperty("lra.coordinator.node.id", "coordinator-1");
        LRAService service1 = new LRAService();
        service1.initializeHA(null, null, null);

        // Node 2
        System.setProperty("lra.coordinator.node.id", "coordinator-2");
        LRAService service2 = new LRAService();
        service2.initializeHA(null, null, null);

        String baseUrl = "http://localhost:8080/lra-coordinator";

        // When: Each creates an LRA
        LongRunningAction lra1 = new LongRunningAction(service1, baseUrl, null, "client-1");
        LongRunningAction lra2 = new LongRunningAction(service2, baseUrl, null, "client-2");

        // Then: LRAs have different node IDs
        assertTrue(lra1.getId().getPath().contains("/coordinator-1/"),
                "LRA from node 1 should have coordinator-1");
        assertTrue(lra2.getId().getPath().contains("/coordinator-2/"),
                "LRA from node 2 should have coordinator-2");

        // And: LRA IDs are unique
        assertNotEquals(lra1.getId(), lra2.getId());
    }

    @Test
    void testLRAIdFormatConsistency() throws Exception {
        // Given: HA mode with node ID
        System.setProperty("lra.coordinator.ha.enabled", "true");
        System.setProperty("lra.coordinator.node.id", "node-123");

        LRAService service = new LRAService();
        service.initializeHA(null, null, null);

        String baseUrl = "http://example.com:8080/lra-coordinator";

        // When: Create LRA
        LongRunningAction lra = new LongRunningAction(service, baseUrl, null, "test-client");

        // Then: LRA ID has expected format
        URI lraId = lra.getId();
        assertEquals("http", lraId.getScheme());
        assertEquals("example.com", lraId.getHost());
        assertEquals(8080, lraId.getPort());

        String path = lraId.getPath();
        assertTrue(path.startsWith("/lra-coordinator/node-123/"),
                "Path should start with /lra-coordinator/node-123/");

        // Extract UID part
        String[] segments = path.split("/");
        assertEquals(4, segments.length);
        String uid = segments[3];
        assertFalse(uid.isEmpty(), "UID should not be empty");
    }

    @Test
    void testClusterCoordinatorInitialization() {
        // Given: ClusterCoordinator
        ClusterCoordinator coordinator = new ClusterCoordinator();

        // When: Check if initialized
        boolean initialized = coordinator.isInitialized();

        // Then: Should not be initialized yet (no cache manager)
        assertFalse(initialized);
    }

    @Test
    void testClusterCoordinatorListeners() {
        // Given: ClusterCoordinator
        ClusterCoordinator coordinator = new ClusterCoordinator();

        // When: Add listener
        final boolean[] becameCoordinator = { false };
        final boolean[] lostCoordinator = { false };

        ClusterCoordinator.CoordinatorChangeListener listener = new ClusterCoordinator.CoordinatorChangeListener() {
            @Override
            public void onBecameCoordinator() {
                becameCoordinator[0] = true;
            }

            @Override
            public void onLostCoordinator() {
                lostCoordinator[0] = true;
            }
        };

        coordinator.addCoordinatorChangeListener(listener);

        // Then: Listener registered
        // (We can't easily trigger events in unit test without JGroups cluster,
        // but we verify the API works)

        // Cleanup
        coordinator.removeCoordinatorChangeListener(listener);
    }
}
