/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.infinispan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.narayana.lra.coordinator.internal.ClusterCoordinationService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link InfinispanClusterCoordinator}.
 *
 * These tests verify:
 * - Single-node coordinator detection (always coordinator in local mode)
 * - Initialization and lifecycle
 * - Listener notifications on coordinator status changes
 * - Idempotent initialization (calling initialize twice is safe)
 * - Shutdown behavior
 */
class InfinispanClusterCoordinatorTest {

    private EmbeddedCacheManager cacheManager;
    private InfinispanClusterCoordinator coordinator;

    @BeforeEach
    void setUp() {
        // Create a local (non-clustered) cache manager for testing
        GlobalConfigurationBuilder globalConfig = new GlobalConfigurationBuilder();
        globalConfig.nonClusteredDefault();
        cacheManager = new DefaultCacheManager(globalConfig.build());

        coordinator = new InfinispanClusterCoordinator();
    }

    @AfterEach
    void tearDown() {
        coordinator.shutdown();
        if (cacheManager != null) {
            cacheManager.stop();
        }
    }

    @Test
    void notInitializedBeforeInitialize() {
        assertFalse(coordinator.isInitialized());
    }

    @Test
    void initializedAfterInitialize() {
        coordinator.initialize(cacheManager);

        assertTrue(coordinator.isInitialized());
    }

    @Test
    void singleNodeIsAlwaysCoordinator() {
        coordinator.initialize(cacheManager);

        // In local (non-clustered) mode, getAddress() returns null,
        // so the coordinator defaults to true (single-node assumption)
        assertTrue(coordinator.isCoordinator());
    }

    @Test
    void initializeWithNullCacheManagerIsNoOp() {
        coordinator.initialize(null);

        assertFalse(coordinator.isInitialized());
        assertFalse(coordinator.isCoordinator());
    }

    @Test
    void doubleInitializeIsIdempotent() {
        coordinator.initialize(cacheManager);
        assertTrue(coordinator.isInitialized());
        assertTrue(coordinator.isCoordinator());

        // Second initialize should be a no-op
        EmbeddedCacheManager otherManager = new DefaultCacheManager(
                new GlobalConfigurationBuilder().nonClusteredDefault().build());
        try {
            coordinator.initialize(otherManager);

            // Should still be initialized with the original cache manager
            assertTrue(coordinator.isInitialized());
            assertTrue(coordinator.isCoordinator());
        } finally {
            otherManager.stop();
        }
    }

    @Test
    void listenerNotifiedOnBecameCoordinator() throws InterruptedException {
        CountDownLatch becameLatch = new CountDownLatch(1);
        CountDownLatch lostLatch = new CountDownLatch(1);

        ClusterCoordinationService.CoordinatorChangeListener listener = new ClusterCoordinationService.CoordinatorChangeListener() {
            @Override
            public void onBecameCoordinator() {
                becameLatch.countDown();
            }

            @Override
            public void onLostCoordinator() {
                lostLatch.countDown();
            }
        };

        // Add listener BEFORE initialize, so it catches the initial coordinator status
        coordinator.addCoordinatorChangeListener(listener);
        coordinator.initialize(cacheManager);

        // In single-node mode the coordinator starts as coordinator,
        // but since isCoordinator starts as false and then becomes true
        // during initialize(), the listener should be notified
        // (the initial checkCoordinatorStatus detects the transition false -> true)
        assertTrue(becameLatch.await(5, TimeUnit.SECONDS),
                "Listener should have been notified that this node became coordinator");
        assertEquals(1, lostLatch.getCount(),
                "Listener should NOT have been notified of losing coordinator status");
    }

    @Test
    void addAndRemoveListener() {
        coordinator.initialize(cacheManager);

        CountDownLatch latch = new CountDownLatch(1);
        ClusterCoordinationService.CoordinatorChangeListener listener = new ClusterCoordinationService.CoordinatorChangeListener() {
            @Override
            public void onBecameCoordinator() {
                latch.countDown();
            }

            @Override
            public void onLostCoordinator() {
            }
        };

        coordinator.addCoordinatorChangeListener(listener);
        coordinator.removeCoordinatorChangeListener(listener);

        // After removal, the listener should not be notified
        // (we can't easily trigger a view change in local mode,
        // but we verify the remove doesn't throw)
        assertEquals(1, latch.getCount());
    }

    @Test
    void shutdownIsIdempotent() {
        coordinator.initialize(cacheManager);

        // First shutdown
        coordinator.shutdown();

        // Second shutdown should not throw
        coordinator.shutdown();
    }

    @Test
    void shutdownWithoutInitializeIsNoOp() {
        // Should not throw
        coordinator.shutdown();
    }

    @Test
    void multipleListenersAllNotified() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(3);

        for (int i = 0; i < 3; i++) {
            coordinator.addCoordinatorChangeListener(
                    new ClusterCoordinationService.CoordinatorChangeListener() {
                        @Override
                        public void onBecameCoordinator() {
                            latch.countDown();
                        }

                        @Override
                        public void onLostCoordinator() {
                        }
                    });
        }

        coordinator.initialize(cacheManager);

        assertTrue(latch.await(5, TimeUnit.SECONDS),
                "All 3 listeners should have been notified");
    }

    @Test
    void exceptionInListenerDoesNotPreventOtherNotifications() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        // Add a listener that throws
        coordinator.addCoordinatorChangeListener(
                new ClusterCoordinationService.CoordinatorChangeListener() {
                    @Override
                    public void onBecameCoordinator() {
                        throw new RuntimeException("Intentional test exception");
                    }

                    @Override
                    public void onLostCoordinator() {
                    }
                });

        // Add a second listener that should still be notified
        coordinator.addCoordinatorChangeListener(
                new ClusterCoordinationService.CoordinatorChangeListener() {
                    @Override
                    public void onBecameCoordinator() {
                        latch.countDown();
                    }

                    @Override
                    public void onLostCoordinator() {
                    }
                });

        coordinator.initialize(cacheManager);

        assertTrue(latch.await(5, TimeUnit.SECONDS),
                "Second listener should be notified even if first listener threw");
    }
}
