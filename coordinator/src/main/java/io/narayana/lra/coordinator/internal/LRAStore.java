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
     * Saves LRA state with compare-and-swap semantics.
     * For new LRAs (expectedVersion == 0), uses putIfAbsent.
     * For existing LRAs, uses replace with version check.
     *
     * @param lraId the LRA identifier
     * @param state the LRA state to save
     * @param expectedVersion the version the caller expects to be current
     * @return the saved LRAState (with incremented version)
     * @throws StaleStateException if version conflict detected
     */
    LRAState saveOrFail(URI lraId, LRAState state, long expectedVersion);

    /**
     * Saves an LRA to the appropriate storage location based on its status.
     * Default implementation delegates to {@link #saveOrFail} for backward compatibility,
     * swallowing version conflicts (last-write-wins).
     *
     * @param lraId the LRA identifier
     * @param state the LRA state to save
     */
    default void saveLRA(URI lraId, LRAState state) {
        if (state == null) {
            return;
        }
        // Last-write-wins: on version conflict, reload current version and retry
        long version = state.getVersion();
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                saveOrFail(lraId, state, version);
                return;
            } catch (StaleStateException e) {
                LRAState current = loadLRA(lraId);
                version = current != null ? current.getVersion() : 0;
            }
        }
    }

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
     * Moves an LRA to the recovering storage location with CAS semantics.
     *
     * @param lraId the LRA identifier
     * @param state the updated LRA state (typically Closing or Cancelling)
     * @param expectedVersion the version the caller expects to be current
     * @return true if the move succeeded, false if another node already moved it
     */
    boolean moveToRecovering(URI lraId, LRAState state, long expectedVersion);

    /**
     * Moves an LRA to the recovering storage location.
     * Default implementation delegates to the CAS version.
     *
     * @param lraId the LRA identifier
     * @param state the updated LRA state (typically Closing or Cancelling)
     */
    default void moveToRecovering(URI lraId, LRAState state) {
        moveToRecovering(lraId, state, state.getVersion());
    }

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

    /**
     * Loads an LRA from storage by its Arjuna UID.
     *
     * This is needed when the full LRA URI is not available, such as when
     * resolving recovery URLs which only contain the UID segment. The
     * implementation should scan all storage locations (active, recovering,
     * failed) for an entry whose LRA UID matches the given value.
     *
     * @param uid the Arjuna UID string (e.g. "0_ffff0a28054b_9133_5f855916_a7")
     * @return the LRA state, or null if not found
     */
    default LRAState loadLRAByUid(String uid) {
        return null;
    }
}
