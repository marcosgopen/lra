/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.internal;

import java.util.Map;

/**
 * Interface for cluster coordination and leader election in HA mode.
 *
 * Implementations handle:
 * - Detecting cluster membership changes
 * - Electing a coordinator/leader node
 * - Notifying listeners when coordinator status changes
 *
 * In HA mode, only the cluster coordinator performs recovery operations
 * to prevent multiple coordinators from processing the same LRA simultaneously.
 */
public interface ClusterCoordinationService {

    /**
     * Checks if this node is the cluster coordinator/leader.
     *
     * @return true if this node is the coordinator
     */
    boolean isCoordinator();

    /**
     * Checks if cluster coordination is initialized and ready.
     *
     * @return true if initialized
     */
    boolean isInitialized();

    /**
     * Adds a listener to be notified of coordinator status changes.
     *
     * @param listener the listener to add
     */
    void addCoordinatorChangeListener(CoordinatorChangeListener listener);

    /**
     * Removes a previously added coordinator change listener.
     *
     * @param listener the listener to remove
     */
    void removeCoordinatorChangeListener(CoordinatorChangeListener listener);

    /**
     * Returns a cluster-wide distributed map for sharing data across nodes.
     * The map is backed by a replicated Infinispan cache (REPL_SYNC) so writes
     * on any node are immediately visible on all other nodes.
     *
     * @param name the cache name to use as the backing store
     * @return a distributed Map, or null if not available (e.g. single-node or not initialized)
     */
    Map<String, String> getDistributedMap(String name);

    /**
     * Listener interface for coordinator status changes.
     */
    interface CoordinatorChangeListener {
        /**
         * Called when this node becomes the cluster coordinator.
         */
        void onBecameCoordinator();

        /**
         * Called when this node loses cluster coordinator status.
         */
        void onLostCoordinator();
    }
}
