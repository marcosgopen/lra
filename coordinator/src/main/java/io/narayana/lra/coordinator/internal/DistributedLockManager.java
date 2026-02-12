/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.internal;

import io.narayana.lra.logging.LRALogger;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.infinispan.lock.api.ClusteredLock;
import org.infinispan.lock.api.ClusteredLockManager;
import org.infinispan.manager.EmbeddedCacheManager;

/**
 * Manages distributed locks for LRAs using Infinispan Clustered Locks.
 *
 * In HA mode, distributed locks ensure that only one coordinator at a time
 * can modify a specific LRA, preventing conflicts when multiple coordinators
 * can access the same LRA state.
 *
 * This is critical for the requirement: "any coordinator can manage any LRA"
 */
@ApplicationScoped
public class DistributedLockManager {

    private ClusteredLockManager lockManager;
    private final ConcurrentHashMap<URI, ClusteredLock> locks = new ConcurrentHashMap<>();
    private boolean initialized = false;

    /**
     * Initializes the lock manager with the given cache manager.
     *
     * @param cacheManager the Infinispan cache manager
     */
    public void initialize(EmbeddedCacheManager cacheManager) {
        if (initialized || cacheManager == null) {
            return;
        }

        try {
            this.lockManager = cacheManager.getClusteredLockManager();
            this.initialized = true;
            LRALogger.logger.info("DistributedLockManager initialized");
        } catch (Exception e) {
            LRALogger.logger.warnf(e, "Failed to initialize DistributedLockManager");
        }
    }

    /**
     * Acquires a distributed lock for an LRA (blocking).
     *
     * @param lraId the LRA ID
     * @return a lock handle, or null if the lock cannot be acquired
     */
    public LockHandle acquireLock(URI lraId) {
        if (!initialized) {
            return null;
        }

        try {
            ClusteredLock lock = getOrCreateLock(lraId);
            lock.lock().join(); // Blocking call
            return new LockHandle(lraId, lock);
        } catch (Exception e) {
            LRALogger.logger.warnf(e, "Failed to acquire distributed lock for LRA %s", lraId);
            return null;
        }
    }

    /**
     * Tries to acquire a distributed lock for an LRA with timeout.
     *
     * @param lraId the LRA ID
     * @param timeout the timeout value
     * @param unit the timeout unit
     * @return a lock handle, or null if the lock cannot be acquired
     */
    public LockHandle acquireLock(URI lraId, long timeout, TimeUnit unit) {
        if (!initialized) {
            return null;
        }

        try {
            ClusteredLock lock = getOrCreateLock(lraId);
            boolean acquired = lock.tryLock(timeout, unit).join();
            if (acquired) {
                return new LockHandle(lraId, lock);
            } else {
                return null;
            }
        } catch (Exception e) {
            LRALogger.logger.warnf(e, "Failed to acquire distributed lock for LRA %s", lraId);
            return null;
        }
    }

    /**
     * Gets or creates a clustered lock for an LRA.
     *
     * @param lraId the LRA ID
     * @return the clustered lock
     */
    private ClusteredLock getOrCreateLock(URI lraId) {
        return locks.computeIfAbsent(lraId, id -> {
            String lockName = "lra-lock-" + sanitizeLockName(id.toString());
            // Define the lock if it doesn't exist
            lockManager.defineLock(lockName);
            return lockManager.get(lockName);
        });
    }

    /**
     * Sanitizes an LRA ID for use as a lock name.
     * Replaces characters that might cause issues in lock names.
     *
     * @param lraId the LRA ID
     * @return the sanitized lock name
     */
    private String sanitizeLockName(String lraId) {
        return lraId.replaceAll("[^a-zA-Z0-9-]", "-");
    }

    /**
     * Checks if the lock manager is initialized.
     *
     * @return true if initialized
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Handle for a distributed lock that must be released when done.
     */
    public static class LockHandle {
        private final URI lraId;
        private final ClusteredLock lock;
        private boolean released = false;

        private LockHandle(URI lraId, ClusteredLock lock) {
            this.lraId = lraId;
            this.lock = lock;
        }

        /**
         * Releases the distributed lock.
         */
        public void release() {
            if (released) {
                return;
            }

            try {
                lock.unlock().join();
                released = true;

                if (LRALogger.logger.isTraceEnabled()) {
                    LRALogger.logger.tracef("Released distributed lock for LRA %s", lraId);
                }
            } catch (Exception e) {
                LRALogger.logger.warnf(e, "Failed to release distributed lock for LRA %s", lraId);
            }
        }

        public URI getLraId() {
            return lraId;
        }

        public boolean isReleased() {
            return released;
        }
    }
}
