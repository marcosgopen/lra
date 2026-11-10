/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.arquillian.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            service.initializeHA();

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
            service.initializeHA();

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
            service.initializeHA();

            // When/Then: Should return some value (either Narayana node id or fallback)
            String nodeId = service.getNodeId();
            assertTrue(nodeId.startsWith("node-"),
                    "Fallback node ID should start with 'node-', got: " + nodeId);
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
            service.initializeHA();
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
            service.initializeHA();

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
                service.initializeHA();

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

    @Test
    void testParticipantSurvivesDeactivateActivateCycle() throws Exception {
        io.narayana.lra.coordinator.internal.Implementations.install();

        LRAService service = new LRAService();
        String baseUrl = "http://localhost:8080/lra-coordinator";
        String recoveryUrlBase = baseUrl + "/lra-coordinator/recovery";

        LongRunningAction lra = new LongRunningAction(service, baseUrl, null, "test-client");
        lra.begin(0L);

        String participantUrl = "<http://example.com/compensate>;rel=\"compensate\","
                + "<http://example.com/complete>;rel=\"complete\"";
        var participant = lra.enlistParticipant(lra.getId(), participantUrl, recoveryUrlBase, 0L, null, null);
        assertTrue(participant.getRecoveryURI().toASCIIString().contains("recovery"),
                "Enlisted participant should have a recovery URI: " + participant.getRecoveryURI());

        assertTrue(lra.deactivate(), "deactivate after enlist should succeed");

        com.arjuna.ats.arjuna.common.Uid uid = lra.get_uid();
        LongRunningAction restored = new LongRunningAction(service, uid);
        assertTrue(restored.activate(), "activate from ObjectStore should succeed");

        assertEquals(lra.getId(), restored.getId(), "Restored LRA should have the same ID");
        assertTrue(restored.hasPendingActions(),
                "Restored LRA should have pending participant records after activate");

        String recoveryUrl = participant.getRecoveryURI().toASCIIString();
        String participantResult = restored.lookupParticipantUrl(recoveryUrl);
        assertTrue(participantResult.contains("example.com"),
                "lookupParticipantUrl should return the original participant URL. recoveryUrl=" + recoveryUrl
                        + " got: " + participantResult);

        // Also test cross-node scenario: different host but same path
        String crossNodeUrl = recoveryUrl.replace("localhost:8080", "localhost:8180");
        String crossNodeResult = restored.lookupParticipantUrl(crossNodeUrl);
        assertTrue(crossNodeResult.contains("example.com"),
                "lookupParticipantUrl should find participant via path matching. crossNodeUrl=" + crossNodeUrl
                        + " got: " + crossNodeResult);

        // Now simulate what happens in HA: StoreManager.shutdown() between write and read.
        // This creates a new store instance, similar to refreshObjectStoreForRecovery().
        com.arjuna.ats.arjuna.objectstore.StoreManager.shutdown();

        LongRunningAction restored2 = new LongRunningAction(service, uid);
        assertTrue(restored2.activate(), "activate after StoreManager.shutdown should succeed");
        assertTrue(restored2.hasPendingActions(),
                "Restored LRA should have pending records after StoreManager.shutdown + activate");

        String result2 = restored2.lookupParticipantUrl(crossNodeUrl);
        assertTrue(result2.contains("example.com"),
                "lookupParticipantUrl should return participant URL after StoreManager.shutdown + activate, got: "
                        + result2);

        // Verify that the stateStore named bean reads from the same store as the default bean.
        // In WildFly, StateManager.activate() uses StoreManager.setupStore("stateStore"),
        // while BasicAction.deactivate() uses StoreManager.getParticipantStore() (default).
        // If they use different ObjectStore types, writes and reads go to different stores.
        var defaultBean = com.arjuna.common.internal.util.propertyservice.BeanPopulator
                .getDefaultInstance(com.arjuna.ats.arjuna.common.ObjectStoreEnvironmentBean.class);
        var stateStoreBean = com.arjuna.common.internal.util.propertyservice.BeanPopulator
                .getNamedInstance(com.arjuna.ats.arjuna.common.ObjectStoreEnvironmentBean.class, "stateStore");

        assertEquals(defaultBean.getObjectStoreType(), stateStoreBean.getObjectStoreType(),
                "Default and stateStore ObjectStoreEnvironmentBeans should use the same store type. "
                        + "If they differ, writes (via default) go to one store and reads (via stateStore) go to another.");

        // Simulate what happens in WildFly HA: only the default bean is changed to SlotStoreAdaptor.
        // The stateStore bean retains the old type. This is the bug.
        String slotStoreType = "com.arjuna.ats.internal.arjuna.objectstore.slot.SlotStoreAdaptor";
        String originalDefault = defaultBean.getObjectStoreType();
        String originalState = stateStoreBean.getObjectStoreType();

        defaultBean.setObjectStoreType(slotStoreType);
        // stateStoreBean NOT updated — simulates the bug

        assertFalse(defaultBean.getObjectStoreType().equals(stateStoreBean.getObjectStoreType()),
                "BUG REPRODUCED: default and stateStore now use different types, "
                        + "meaning deactivate() writes to SlotStoreAdaptor but activate() reads from "
                        + stateStoreBean.getObjectStoreType());

        // Restore
        defaultBean.setObjectStoreType(originalDefault);
    }

    @Test
    void testInfinispanObjectStoreRoundTrip() throws Exception {
        org.infinispan.configuration.global.GlobalConfigurationBuilder gcb = new org.infinispan.configuration.global.GlobalConfigurationBuilder();
        gcb.nonClusteredDefault();
        org.infinispan.manager.EmbeddedCacheManager cm = new org.infinispan.manager.DefaultCacheManager(gcb.build());

        org.infinispan.configuration.cache.ConfigurationBuilder cb = new org.infinispan.configuration.cache.ConfigurationBuilder();
        cb.clustering().cacheMode(org.infinispan.configuration.cache.CacheMode.LOCAL);
        cm.defineConfiguration("test-objectstore", cb.build());
        org.infinispan.Cache<String, byte[]> cache = cm.getCache("test-objectstore");

        try {
            io.narayana.lra.coordinator.infinispan.InfinispanObjectStore store = new io.narayana.lra.coordinator.infinispan.InfinispanObjectStore();
            io.narayana.lra.coordinator.infinispan.InfinispanObjectStore.setCache(cache);

            com.arjuna.ats.arjuna.common.Uid uid1 = new com.arjuna.ats.arjuna.common.Uid();
            com.arjuna.ats.arjuna.common.Uid uid2 = new com.arjuna.ats.arjuna.common.Uid();
            String type = "/StateManager/BasicAction/LongRunningAction";

            com.arjuna.ats.arjuna.state.OutputObjectState out1 = new com.arjuna.ats.arjuna.state.OutputObjectState();
            out1.packString("test-data-1");
            assertTrue(store.write_committed(uid1, type, out1), "write_committed should succeed");

            com.arjuna.ats.arjuna.state.OutputObjectState out2 = new com.arjuna.ats.arjuna.state.OutputObjectState();
            out2.packString("test-data-2");
            assertTrue(store.write_committed(uid2, type, out2), "write_committed should succeed");

            com.arjuna.ats.arjuna.state.InputObjectState in1 = store.read_committed(uid1, type);
            assertEquals("test-data-1", in1.unpackString(), "Data should round-trip correctly for uid1");

            com.arjuna.ats.arjuna.state.InputObjectState in2 = store.read_committed(uid2, type);
            assertEquals("test-data-2", in2.unpackString(), "Data should round-trip correctly for uid2");

            com.arjuna.ats.arjuna.state.InputObjectState allUids = new com.arjuna.ats.arjuna.state.InputObjectState();
            assertTrue(store.allObjUids(type, allUids), "allObjUids should succeed");

            java.util.Set<String> foundUids = new java.util.HashSet<>();
            com.arjuna.ats.arjuna.common.Uid u;
            while ((u = com.arjuna.ats.internal.arjuna.common.UidHelper.unpackFrom(allUids)) != null
                    && !u.equals(com.arjuna.ats.arjuna.common.Uid.nullUid())) {
                foundUids.add(u.fileStringForm());
            }
            assertEquals(2, foundUids.size(), "allObjUids should return 2 UIDs");
            assertTrue(foundUids.contains(uid1.fileStringForm()), "Should contain uid1");
            assertTrue(foundUids.contains(uid2.fileStringForm()), "Should contain uid2");

            assertTrue(store.remove_committed(uid1, type), "remove_committed should succeed");
            assertEquals(null, store.read_committed(uid1, type), "Removed entry should not be readable");
            assertEquals("test-data-2", store.read_committed(uid2, type).unpackString(),
                    "Non-removed entry should still be readable");

            assertEquals(com.arjuna.ats.arjuna.objectstore.StateStatus.OS_COMMITTED,
                    store.currentState(uid2, type), "Existing entry should be OS_COMMITTED");
            assertEquals(com.arjuna.ats.arjuna.objectstore.StateStatus.OS_UNKNOWN,
                    store.currentState(uid1, type), "Removed entry should be OS_UNKNOWN");
        } finally {
            io.narayana.lra.coordinator.infinispan.InfinispanObjectStore.setCache(null);
            cm.stop();
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
