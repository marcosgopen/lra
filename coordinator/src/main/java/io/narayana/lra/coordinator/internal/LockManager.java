/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.internal;

import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * Interface for distributed lock management in HA mode.
 *
 * Implementations provide distributed locking to ensure that only one coordinator
 * at a time can modify a specific LRA, preventing conflicts when multiple
 * coordinators can access the same LRA state.
 *
 * This is critical for the requirement: "any coordinator can manage any LRA"
 */
public interface LockManager {

    /**
     * Acquires a distributed lock for an LRA (blocking).
     *
     * @param lraId the LRA identifier
     * @return a lock handle, or null if the lock cannot be acquired
     */
    LockHandle acquireLock(URI lraId);

    /**
     * Tries to acquire a distributed lock for an LRA with a timeout.
     *
     * @param lraId the LRA identifier
     * @param timeout the timeout value
     * @param unit the timeout unit
     * @return a lock handle, or null if the lock cannot be acquired within the timeout
     */
    LockHandle acquireLock(URI lraId, long timeout, TimeUnit unit);

    /**
     * Checks if the lock manager is initialized and ready.
     *
     * @return true if initialized
     */
    boolean isInitialized();

    /**
     * Handle for a distributed lock that must be released when done.
     */
    interface LockHandle {
        /**
         * Releases the distributed lock.
         */
        void release();

        /**
         * Gets the LRA ID this lock is for.
         *
         * @return the LRA identifier
         */
        URI getLraId();

        /**
         * Checks if this lock has been released.
         *
         * @return true if released
         */
        boolean isReleased();
    }
}
