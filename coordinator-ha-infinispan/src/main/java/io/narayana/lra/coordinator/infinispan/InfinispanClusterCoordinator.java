/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.infinispan;

import io.narayana.lra.coordinator.internal.ClusterCoordinationService;
import io.narayana.lra.logging.LRALogger;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachemanagerlistener.annotation.ViewChanged;
import org.infinispan.notifications.cachemanagerlistener.event.ViewChangedEvent;
import org.infinispan.remoting.transport.Address;

/**
 * Infinispan-based implementation of cluster coordination and leader election.
 *
 * Uses Infinispan's built-in coordinator election (based on JGroups view).
 * This is simpler than Raft and sufficient for LRA recovery coordination.
 *
 * In HA mode, only the cluster coordinator performs recovery operations.
 * This prevents multiple coordinators from trying to recover the same LRA
 * simultaneously.
 */
@ApplicationScoped
@Listener
public class InfinispanClusterCoordinator implements ClusterCoordinationService {

    private EmbeddedCacheManager cacheManager;
    private boolean initialized = false;
    private volatile boolean isCoordinator = false;
    private final List<ClusterCoordinationService.CoordinatorChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    /**
     * Initializes cluster coordination using the Infinispan cache manager.
     * Uses Infinispan's built-in cluster view and coordinator election.
     *
     * @param cacheManager the Infinispan cache manager
     */
    public void initialize(EmbeddedCacheManager cacheManager) {
        if (initialized || cacheManager == null) {
            return;
        }

        try {
            this.cacheManager = cacheManager;

            // Register as listener for view change events
            cacheManager.addListener(this);

            // Check initial coordinator status
            checkCoordinatorStatus();

            // Schedule periodic coordinator check as fallback (in case we miss events)
            scheduler.scheduleWithFixedDelay(this::checkCoordinatorStatus, 10, 10, TimeUnit.SECONDS);

            initialized = true;
            LRALogger.logger.infof("InfinispanClusterCoordinator initialized (isCoordinator=%s)", isCoordinator);

        } catch (Exception e) {
            LRALogger.logger.warnf(e, "Failed to initialize InfinispanClusterCoordinator");
        }
    }

    /**
     * Infinispan ViewChanged listener - called when cluster membership changes.
     *
     * @param event the view changed event
     */
    @ViewChanged
    public void viewChanged(ViewChangedEvent event) {
        if (LRALogger.logger.isDebugEnabled()) {
            LRALogger.logger.debugf("Cluster view changed: new view size=%d", event.getNewMembers().size());
        }
        checkCoordinatorStatus();
    }

    /**
     * Checks and updates coordinator status based on Infinispan's cluster view.
     * The coordinator is determined by Infinispan (first member in JGroups view).
     */
    private void checkCoordinatorStatus() {
        if (cacheManager == null) {
            return;
        }

        try {
            Address localAddress = cacheManager.getAddress();
            Address coordinatorAddress = cacheManager.getCoordinator();

            boolean wasCoordinator = isCoordinator;

            if (localAddress == null || coordinatorAddress == null) {
                // Single-node or local mode — this node is always coordinator
                isCoordinator = true;
            } else {
                isCoordinator = localAddress.equals(coordinatorAddress);
            }

            if (LRALogger.logger.isDebugEnabled()) {
                LRALogger.logger.debugf("Coordinator status: %s (local=%s, coordinator=%s)",
                        isCoordinator ? "COORDINATOR" : "FOLLOWER", localAddress, coordinatorAddress);
            }

            // Notify listeners of coordinator change
            if (isCoordinator && !wasCoordinator) {
                LRALogger.logger.info("This node became the cluster coordinator");
                notifyBecameCoordinator();
            } else if (!isCoordinator && wasCoordinator) {
                LRALogger.logger.info("This node lost cluster coordinator status");
                notifyLostCoordinator();
            }
        } catch (Exception e) {
            LRALogger.logger.warnf(e, "Error checking coordinator status");
        }
    }

    /**
     * Adds a coordinator change listener.
     *
     * @param listener the listener
     */
    @Override
    public void addCoordinatorChangeListener(ClusterCoordinationService.CoordinatorChangeListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes a coordinator change listener.
     *
     * @param listener the listener
     */
    @Override
    public void removeCoordinatorChangeListener(ClusterCoordinationService.CoordinatorChangeListener listener) {
        listeners.remove(listener);
    }

    /**
     * Notifies listeners that this node became the coordinator.
     */
    private void notifyBecameCoordinator() {
        for (ClusterCoordinationService.CoordinatorChangeListener listener : listeners) {
            try {
                listener.onBecameCoordinator();
            } catch (Exception e) {
                LRALogger.logger.warnf(e, "Error notifying coordinator change listener");
            }
        }
    }

    /**
     * Notifies listeners that this node lost coordinator status.
     */
    private void notifyLostCoordinator() {
        for (ClusterCoordinationService.CoordinatorChangeListener listener : listeners) {
            try {
                listener.onLostCoordinator();
            } catch (Exception e) {
                LRALogger.logger.warnf(e, "Error notifying coordinator change listener");
            }
        }
    }

    /**
     * Checks if this node is the cluster coordinator.
     *
     * @return true if this node is the coordinator
     */
    @Override
    public boolean isCoordinator() {
        return isCoordinator;
    }

    /**
     * Checks if cluster coordination is initialized.
     *
     * @return true if initialized
     */
    @Override
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Shuts down cluster coordination.
     */
    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        if (cacheManager != null) {
            try {
                cacheManager.removeListener(this);
                LRALogger.logger.info("Shutting down InfinispanClusterCoordinator");
            } catch (Exception e) {
                LRALogger.logger.warnf(e, "Error during InfinispanClusterCoordinator shutdown");
            }
        }
    }
}
