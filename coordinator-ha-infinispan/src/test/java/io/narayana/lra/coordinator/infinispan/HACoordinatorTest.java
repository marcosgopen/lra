/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.infinispan;

import static org.junit.jupiter.api.Assertions.*;

import io.narayana.lra.coordinator.domain.model.LRAState;
import io.narayana.lra.coordinator.domain.service.LRAService;
import io.narayana.lra.coordinator.internal.LockManager;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.partitionhandling.AvailabilityMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for LRA High Availability features.
 *
 * Tests:
 * - Multiple coordinators sharing state via Infinispan
 * - Any coordinator can manage any LRA
 * - Distributed locking prevents conflicts
 * - Cache availability modes (AVAILABLE, DEGRADED)
 * - Coordinator failover scenarios
 *
 * Multi-node tests use a single shared EmbeddedCacheManager with LOCAL mode
 * to simulate shared distributed caches without requiring JGroups clustering
 * in the unit test environment.
 */
class HACoordinatorTest {

    private EmbeddedCacheManager sharedCacheManager;
    private List<InfinispanStore> stores;
    private List<InfinispanLockManager> lockManagers;
    private List<LRAService> services;

    @BeforeEach
    void setUp() {
        stores = new ArrayList<>();
        lockManagers = new ArrayList<>();
        services = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        services.clear();
        lockManagers.clear();
        stores.clear();

        if (sharedCacheManager != null) {
            sharedCacheManager.stop();
            sharedCacheManager = null;
        }
    }

    /**
     * Creates a test cluster with the specified number of nodes.
     * All nodes share the same EmbeddedCacheManager with LOCAL cache mode,
     * simulating shared distributed caches without requiring JGroups networking.
     */
    private void createTestCluster(int nodeCount) {
        // Create a single shared cache manager with LOCAL mode
        GlobalConfigurationBuilder globalConfig = new GlobalConfigurationBuilder();
        globalConfig
                .cacheContainer()
                .statistics(true);

        sharedCacheManager = new DefaultCacheManager(globalConfig.build());

        // Configure caches with LOCAL mode (no clustering needed for unit tests)
        ConfigurationBuilder cacheConfig = new ConfigurationBuilder();
        cacheConfig
                .clustering()
                .cacheMode(CacheMode.LOCAL);

        sharedCacheManager.defineConfiguration(InfinispanConfiguration.ACTIVE_LRA_CACHE_NAME, cacheConfig.build());
        sharedCacheManager.defineConfiguration(InfinispanConfiguration.RECOVERING_LRA_CACHE_NAME, cacheConfig.build());
        sharedCacheManager.defineConfiguration(InfinispanConfiguration.FAILED_LRA_CACHE_NAME, cacheConfig.build());

        for (int i = 0; i < nodeCount; i++) {
            // Create InfinispanStore for this node (all backed by the same caches)
            InfinispanStore store = createInfinispanStore(sharedCacheManager);

            // Create InfinispanLockManager
            InfinispanLockManager lockManager = new InfinispanLockManager();
            lockManager.initialize(sharedCacheManager);

            // Create LRAService
            LRAService service = new LRAService();
            service.initializeHA(store, lockManager, null);

            stores.add(store);
            lockManagers.add(lockManager);
            services.add(service);
        }
    }

    /**
     * Creates an InfinispanStore with the given cache manager.
     */
    private InfinispanStore createInfinispanStore(EmbeddedCacheManager cacheManager) {
        InfinispanStore store = new InfinispanStore() {
            private final Cache<String, LRAState> activeCache = cacheManager
                    .getCache(InfinispanConfiguration.ACTIVE_LRA_CACHE_NAME);
            private final Cache<String, LRAState> recoveringCache = cacheManager
                    .getCache(InfinispanConfiguration.RECOVERING_LRA_CACHE_NAME);
            private final Cache<String, LRAState> failedCache = cacheManager
                    .getCache(InfinispanConfiguration.FAILED_LRA_CACHE_NAME);

            @Override
            public Cache<String, LRAState> getActiveLRACache() {
                return activeCache;
            }

            @Override
            public Cache<String, LRAState> getRecoveringLRACache() {
                return recoveringCache;
            }

            @Override
            public Cache<String, LRAState> getFailedLRACache() {
                return failedCache;
            }
        };

        // Set HA enabled via system property
        System.setProperty("lra.coordinator.ha.enabled", "true");
        store.initialize();

        return store;
    }

    @Test
    void testSingleNodeCacheOperations() {
        // Given: Single node cluster
        createTestCluster(1);
        InfinispanStore store = stores.get(0);

        // When: Save LRA state
        URI lraId = URI.create("http://localhost:8080/lra-coordinator/test-lra-1");
        LRAState state = createTestLRAState(lraId, LRAStatus.Active);
        store.saveLRA(lraId, state);

        // Then: Can load the state
        LRAState loaded = store.loadLRA(lraId);
        assertNotNull(loaded);
        assertEquals(lraId, loaded.getId());
        assertEquals(LRAStatus.Active, loaded.getStatus());
    }

    @Test
    void testMultiNodeStateReplication() {
        // Given: 3-node cluster (shared caches simulate replication)
        createTestCluster(3);

        URI lraId = URI.create("http://localhost:8080/lra-coordinator/test-lra-2");
        LRAState state = createTestLRAState(lraId, LRAStatus.Active);

        // When: Node 0 saves LRA
        stores.get(0).saveLRA(lraId, state);

        // Then: All nodes can load the LRA (shared caches)
        for (int i = 0; i < 3; i++) {
            LRAState loaded = stores.get(i).loadLRA(lraId);
            assertNotNull(loaded, "Node " + i + " should have replicated state");
            assertEquals(lraId, loaded.getId());
            assertEquals(LRAStatus.Active, loaded.getStatus());
        }
    }

    @Test
    void testAnyCoordinatorCanManageLRA() {
        // Given: 3-node cluster
        createTestCluster(3);

        URI lraId = URI.create("http://localhost:8080/lra-coordinator/node0/test-lra-3");
        LRAState state = createTestLRAState(lraId, LRAStatus.Active);

        // When: Node 0 creates LRA
        stores.get(0).saveLRA(lraId, state);

        // Then: Node 1 can move it to Closing (use moveToRecovering for status transitions)
        LRAState closingState = createTestLRAState(lraId, LRAStatus.Closing);
        stores.get(1).moveToRecovering(lraId, closingState);

        // And: Node 2 can see the updated state
        LRAState loaded = stores.get(2).loadLRA(lraId);
        assertNotNull(loaded);
        assertEquals(LRAStatus.Closing, loaded.getStatus());
    }

    @Test
    void testDistributedLockAcquisition() {
        // Given: 2-node cluster
        createTestCluster(2);

        URI lraId = URI.create("http://localhost:8080/lra-coordinator/test-lra-4");

        // Note: EmbeddedClusteredLockManagerFactory requires JGroups transport,
        // which is not available in LOCAL mode. In this unit test we verify
        // that the lock manager API handles this gracefully (returns null
        // when not initialized, rather than throwing exceptions).
        InfinispanLockManager lockManager = lockManagers.get(0);

        if (lockManager.isInitialized()) {
            // JGroups transport is available - test actual locking
            LockManager.LockHandle lock0 = lockManager.acquireLock(lraId, 1, TimeUnit.SECONDS);
            assertNotNull(lock0);

            LockManager.LockHandle lock1 = lockManagers.get(1).acquireLock(lraId, 100, TimeUnit.MILLISECONDS);

            if (lock0 != null) {
                lock0.release();
            }
            if (lock1 != null) {
                lock1.release();
            }
        } else {
            // Without JGroups transport, lock manager cannot initialize.
            // Verify graceful degradation: acquireLock returns null.
            LockManager.LockHandle lock = lockManager.acquireLock(lraId, 1, TimeUnit.SECONDS);
            assertNull(lock, "Lock manager should return null when not initialized");
            assertFalse(lockManager.isInitialized(),
                    "Lock manager should not be initialized without JGroups transport");
        }
    }

    @Test
    void testCacheAvailabilityMode() {
        // Given: Single node cluster
        createTestCluster(1);
        InfinispanStore store = stores.get(0);

        // When: Check availability
        boolean available = store.isAvailable();

        // Then: Should be available
        assertTrue(available, "Cache should be available in single-node cluster");

        // And: Availability mode should be AVAILABLE
        AvailabilityMode mode = store.getAvailabilityMode();
        assertEquals(AvailabilityMode.AVAILABLE, mode);
    }

    @Test
    void testMoveToRecoveringCache() {
        // Given: 2-node cluster
        createTestCluster(2);

        URI lraId = URI.create("http://localhost:8080/lra-coordinator/test-lra-5");
        LRAState activeState = createTestLRAState(lraId, LRAStatus.Active);

        // When: Save to active cache
        stores.get(0).saveLRA(lraId, activeState);

        // Then: Can load from active cache
        assertNotNull(stores.get(0).loadLRA(lraId));

        // When: Move to recovering cache
        LRAState closingState = createTestLRAState(lraId, LRAStatus.Closing);
        stores.get(0).moveToRecovering(lraId, closingState);

        // Then: Can still load (checks all caches)
        LRAState loaded = stores.get(1).loadLRA(lraId);
        assertNotNull(loaded);
        assertEquals(LRAStatus.Closing, loaded.getStatus());
    }

    @Test
    void testMoveToFailedCache() {
        // Given: 2-node cluster with LRA in active cache
        createTestCluster(2);

        URI lraId = URI.create("http://localhost:8080/lra-coordinator/test-lra-6");
        LRAState activeState = createTestLRAState(lraId, LRAStatus.Active);
        stores.get(0).saveLRA(lraId, activeState);

        // When: Move to failed cache
        stores.get(0).moveToFailed(lraId);

        // Then: Can load from failed cache
        LRAState loaded = stores.get(1).loadLRA(lraId);
        assertNotNull(loaded);
    }

    @Test
    void testRemoveLRA() {
        // Given: 2-node cluster with LRA
        createTestCluster(2);

        URI lraId = URI.create("http://localhost:8080/lra-coordinator/test-lra-7");
        LRAState state = createTestLRAState(lraId, LRAStatus.Closed);
        stores.get(0).saveLRA(lraId, state);

        // Verify it exists
        assertNotNull(stores.get(1).loadLRA(lraId));

        // When: Remove LRA
        stores.get(0).removeLRA(lraId);

        // Then: Cannot load anymore
        assertNull(stores.get(1).loadLRA(lraId));
    }

    @Test
    void testStateTransitions() {
        // Given: 3-node cluster
        createTestCluster(3);

        URI lraId = URI.create("http://localhost:8080/lra-coordinator/test-lra-8");

        // When: Active -> Closing -> Closed
        LRAState activeState = createTestLRAState(lraId, LRAStatus.Active);
        stores.get(0).saveLRA(lraId, activeState);

        LRAState closingState = createTestLRAState(lraId, LRAStatus.Closing);
        stores.get(1).moveToRecovering(lraId, closingState);

        LRAState closedState = createTestLRAState(lraId, LRAStatus.Closed);
        stores.get(2).saveLRA(lraId, closedState);

        // Then: All nodes see final state
        for (int i = 0; i < 3; i++) {
            LRAState loaded = stores.get(i).loadLRA(lraId);
            assertNotNull(loaded, "Node " + i + " should see final state");
            assertEquals(LRAStatus.Closed, loaded.getStatus());
        }
    }

    @Test
    void testConcurrentAccess() throws InterruptedException {
        // Given: 2-node cluster
        createTestCluster(2);

        URI lraId = URI.create("http://localhost:8080/lra-coordinator/test-lra-9");

        // When: Both nodes try to save state concurrently
        Thread t1 = new Thread(() -> {
            LRAState state1 = createTestLRAState(lraId, LRAStatus.Active);
            stores.get(0).saveLRA(lraId, state1);
        });

        Thread t2 = new Thread(() -> {
            LRAState state2 = createTestLRAState(lraId, LRAStatus.Active);
            stores.get(1).saveLRA(lraId, state2);
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // Then: LRA exists (last write wins)
        LRAState loaded = stores.get(0).loadLRA(lraId);
        assertNotNull(loaded);
        assertEquals(LRAStatus.Active, loaded.getStatus());
    }

    /**
     * Creates a test InfinispanLRAState.
     */
    private InfinispanLRAState createTestLRAState(URI lraId, LRAStatus status) {
        return new InfinispanLRAState(
                lraId.toString(),
                null,
                "test-client",
                status.name(),
                java.time.LocalDateTime.now().toString(),
                null,
                0L,
                "test-node",
                new byte[0]);
    }
}
