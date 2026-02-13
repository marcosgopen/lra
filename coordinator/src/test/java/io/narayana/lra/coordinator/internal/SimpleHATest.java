/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.internal;

import static org.junit.jupiter.api.Assertions.*;

import io.narayana.lra.coordinator.domain.model.LongRunningAction;
import io.narayana.lra.coordinator.domain.service.LRAService;
import io.narayana.lra.coordinator.internal.infinispan.InfinispanClusterCoordinator;
import io.narayana.lra.coordinator.internal.infinispan.InfinispanStore;
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

    private static final String BASE_URL = "http://localhost:8080/lra-coordinator";
    private static final String PROP_HA_ENABLED = "lra.coordinator.ha.enabled";
    private static final String PROP_NODE_ID = "lra.coordinator.node.id";

    private String originalHaEnabled;
    private String originalNodeId;

    @BeforeEach
    void setUp() {
        // Save original system properties
        originalHaEnabled = System.getProperty(PROP_HA_ENABLED);
        originalNodeId = System.getProperty(PROP_NODE_ID);
    }

    @AfterEach
    void tearDown() {
        // Restore original system properties
        restoreProperty(PROP_HA_ENABLED, originalHaEnabled);
        restoreProperty(PROP_NODE_ID, originalNodeId);
    }

    // Helper methods

    private void restoreProperty(String key, String originalValue) {
        if (originalValue != null) {
            System.setProperty(key, originalValue);
        } else {
            System.clearProperty(key);
        }
    }

    private InfinispanStore createMockHAStore() {
        return new InfinispanStore() {
            @Override
            public boolean isHaEnabled() {
                return true;
            }
        };
    }

    private LRAService createHAService(String nodeId) {
        System.setProperty(PROP_HA_ENABLED, "true");
        System.setProperty(PROP_NODE_ID, nodeId);

        LRAService service = new LRAService();
        service.initializeHA(createMockHAStore(), null, null);
        return service;
    }

    private void assertLRAIdSegments(String lraPath, int expectedSegments, String expectedBase, String expectedNodeId) {
        String[] segments = lraPath.split("/");
        assertEquals(expectedSegments, segments.length,
                String.format("Expected %d segments in path: %s", expectedSegments, lraPath));
        assertEquals(expectedBase, segments[1], "Base path should be lra-coordinator");
        if (expectedNodeId != null) {
            assertEquals(expectedNodeId, segments[2], "Node ID segment mismatch");
        }
    }

    @Test
    void testNodeIdEmbeddingInSingleInstanceMode() throws Exception {
        // Given: HA disabled
        System.clearProperty(PROP_HA_ENABLED);
        LRAService service = new LRAService();

        // When: Create LRA
        LongRunningAction lra = new LongRunningAction(service, BASE_URL, null, "test-client");

        // Then: LRA ID does NOT contain node ID
        String lraIdPath = lra.getId().getPath();
        assertFalse(lraIdPath.contains("/node-"), "Single-instance mode should not embed node ID");
        assertLRAIdSegments(lraIdPath, 3, "lra-coordinator", null);
    }

    @Test
    void testNodeIdEmbeddingInHAMode() throws Exception {
        // Given: HA enabled with specific node ID
        LRAService service = createHAService("test-node-1");

        // When: Create LRA
        LongRunningAction lra = new LongRunningAction(service, BASE_URL, null, "test-client");

        // Then: LRA ID contains node ID
        String lraIdPath = lra.getId().getPath();
        assertTrue(lraIdPath.contains("/test-node-1/"), "HA mode should embed node ID in LRA ID");
        assertLRAIdSegments(lraIdPath, 4, "lra-coordinator", "test-node-1");
    }

    @Test
    void testGetNodeIdFromSystemProperty() {
        // Given: Node ID set via system property
        System.setProperty(PROP_NODE_ID, "my-coordinator-1");

        LRAService service = new LRAService();
        service.initializeHA(null, null, null);

        // When/Then: Should return the configured value
        assertEquals("my-coordinator-1", service.getNodeId());
    }

    @Test
    void testGetNodeIdFromEnvironmentFallback() {
        // Given: No system property (relies on HOSTNAME env var or fallback)
        System.clearProperty(PROP_NODE_ID);

        LRAService service = new LRAService();
        service.initializeHA(null, null, null);

        // When/Then: Should return some value (either HOSTNAME or fallback)
        String nodeId = service.getNodeId();
        assertNotNull(nodeId);
        assertFalse(nodeId.isEmpty());
    }

    @Test
    void testIsHaEnabledWhenDisabled() {
        // Given: HA disabled
        System.clearProperty(PROP_HA_ENABLED);

        // When/Then: Should be false
        assertFalse(new LRAService().isHaEnabled());
    }

    @Test
    void testIsHaEnabledWhenEnabled() {
        // Given: HA enabled
        LRAService service = createHAService("test-node");

        // When/Then: Should be true
        assertTrue(service.isHaEnabled());
    }

    @Test
    void testNestedLRAWithNodeId() throws Exception {
        // Given: HA enabled
        LRAService service = createHAService("test-node-2");

        // When: Create parent and nested LRA
        LongRunningAction parent = new LongRunningAction(service, BASE_URL, null, "parent-client");
        LongRunningAction child = new LongRunningAction(service, BASE_URL, parent, "child-client");

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
        LRAService service1 = createHAService("coordinator-1");
        LRAService service2 = createHAService("coordinator-2");

        // When: Each creates an LRA
        LongRunningAction lra1 = new LongRunningAction(service1, BASE_URL, null, "client-1");
        LongRunningAction lra2 = new LongRunningAction(service2, BASE_URL, null, "client-2");

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
        LRAService service = createHAService("node-123");
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

        // Verify UID segment is not empty
        String[] segments = path.split("/");
        assertEquals(4, segments.length);
        assertFalse(segments[3].isEmpty(), "UID should not be empty");
    }

    @Test
    void testClusterCoordinatorInitialization() {
        // Given/When: InfinispanClusterCoordinator without cache manager
        InfinispanClusterCoordinator coordinator = new InfinispanClusterCoordinator();

        // Then: Should not be initialized yet
        assertFalse(coordinator.isInitialized());
    }

    @Test
    void testClusterCoordinatorListeners() {
        // Given: InfinispanClusterCoordinator
        InfinispanClusterCoordinator coordinator = new InfinispanClusterCoordinator();

        boolean[] becameCoordinator = { false };
        boolean[] lostCoordinator = { false };

        ClusterCoordinationService.CoordinatorChangeListener listener = new ClusterCoordinationService.CoordinatorChangeListener() {
            @Override
            public void onBecameCoordinator() {
                becameCoordinator[0] = true;
            }

            @Override
            public void onLostCoordinator() {
                lostCoordinator[0] = true;
            }
        };

        // When: Add and remove listener
        coordinator.addCoordinatorChangeListener(listener);
        coordinator.removeCoordinatorChangeListener(listener);

        // Then: No exceptions thrown (listener API works correctly)
        // Note: Can't trigger events without actual JGroups cluster
    }
}
