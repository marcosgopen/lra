/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.internal;

import io.narayana.lra.coordinator.domain.model.LRAState;
import java.net.URI;
import java.util.Collection;
import java.util.Map;

/**
 * Interface for LRA state storage implementations.
 *
 * This abstraction allows different storage backends to be plugged in
 * (e.g., Infinispan, Redis, Hazelcast, or other distributed cache solutions).
 *
 * Implementations should handle:
 * - Persistent or distributed storage of LRA state
 * - High availability and partition tolerance (if applicable)
 * - Thread-safe concurrent access
 *
 * The store manages three logical categories of LRAs:
 * - Active: LRAs in Active state
 * - Recovering: LRAs in Closing/Cancelling/Closed/Cancelled state
 * - Failed: LRAs in FailedToClose/FailedToCancel state
 */
public interface LRAStore {

    /**
     * Initializes the store and prepares it for use.
     * This method should be called before any other operations.
     */
    void initialize();

    /**
     * Saves an LRA to the appropriate storage location based on its status.
     *
     * @param lraId the LRA identifier
     * @param state the LRA state to save
     */
    void saveLRA(URI lraId, LRAState state);

    /**
     * Loads an LRA from storage.
     * Implementations should check all storage locations (active, recovering, failed).
     *
     * @param lraId the LRA identifier
     * @return the LRA state, or null if not found
     */
    LRAState loadLRA(URI lraId);

    /**
     * Removes an LRA from all storage locations.
     *
     * @param lraId the LRA identifier
     */
    void removeLRA(URI lraId);

    /**
     * Moves an LRA to the recovering storage location.
     * This operation should be atomic if possible.
     *
     * @param lraId the LRA identifier
     * @param state the updated LRA state (typically Closing or Cancelling)
     */
    void moveToRecovering(URI lraId, LRAState state);

    /**
     * Moves an LRA to the failed storage location.
     * This operation should be atomic if possible.
     *
     * @param lraId the LRA identifier
     */
    void moveToFailed(URI lraId);

    /**
     * Checks if high availability mode is enabled for this store.
     *
     * @return true if HA mode is enabled, false for single-instance mode
     */
    boolean isHaEnabled();

    /**
     * Checks if the store is available for operations.
     *
     * For distributed stores with partition handling, this should return false
     * if the store is in a minority partition or otherwise unavailable.
     *
     * For single-instance stores, this typically returns true.
     *
     * @return true if the store is available for read/write operations
     */
    boolean isAvailable();

    /**
     * Retrieves all active LRAs as a map of LRA ID to state.
     *
     * This is used for operations like timeout checking that need to
     * iterate over all active LRAs.
     *
     * @return map of active LRA IDs to their states, empty map if none exist or HA is disabled
     */
    Map<String, LRAState> getAllActiveLRAs();

    /**
     * Retrieves all recovering LRAs as a collection.
     *
     * These are LRAs in Closing, Cancelling, Closed, or Cancelled states
     * that may require recovery processing.
     *
     * @return collection of recovering LRA states, empty collection if none exist or HA is disabled
     */
    Collection<LRAState> getAllRecoveringLRAs();

    /**
     * Retrieves all failed LRAs as a collection.
     *
     * These are LRAs in FailedToClose or FailedToCancel states.
     *
     * @return collection of failed LRA states, empty collection if none exist or HA is disabled
     */
    Collection<LRAState> getAllFailedLRAs();
}
