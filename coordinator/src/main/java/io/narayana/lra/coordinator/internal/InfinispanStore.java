/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.internal;

import io.narayana.lra.coordinator.domain.model.LRAState;
import io.narayana.lra.logging.LRALogger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.net.URI;
import org.infinispan.Cache;
import org.infinispan.partitionhandling.AvailabilityMode;

/**
 * Manages LRA state storage in Infinispan distributed caches.
 *
 * In HA mode, this allows:
 * - Multiple coordinators to share LRA state
 * - Any coordinator to take over management of any LRA
 * - A single recovery manager to recover LRAs from multiple nodes
 *
 * The store uses three caches:
 * - lra-active: Active LRAs
 * - lra-recovering: LRAs in Closing/Cancelling state
 * - lra-failed: Failed LRAs (FailedToClose/FailedToCancel)
 */
@ApplicationScoped
public class InfinispanStore {

    @Inject
    @Named("activeLRACache")
    private Cache<URI, LRAState> activeLRACache;

    @Inject
    @Named("recoveringLRACache")
    private Cache<URI, LRAState> recoveringLRACache;

    @Inject
    @Named("failedLRACache")
    private Cache<URI, LRAState> failedLRACache;

    private boolean haEnabled = false;

    /**
     * Initializes the store and checks if HA mode is enabled.
     */
    public void initialize() {
        String haEnabledProp = System.getProperty("lra.coordinator.ha.enabled", "false");
        this.haEnabled = "true".equalsIgnoreCase(haEnabledProp);

        if (haEnabled) {
            // Use getters instead of fields for testability
            if (getActiveLRACache() == null || getRecoveringLRACache() == null || getFailedLRACache() == null) {
                LRALogger.logger.warn("HA mode enabled but Infinispan caches not available, disabling HA");
                this.haEnabled = false;
            } else {
                LRALogger.logger.info("InfinispanStore initialized in HA mode");
            }
        } else {
            LRALogger.logger.debug("InfinispanStore initialized in single-instance mode");
        }
    }

    /**
     * Saves an LRA to the appropriate cache based on its status.
     *
     * @param lraId the LRA ID
     * @param state the LRA state
     */
    public void saveLRA(URI lraId, LRAState state) {
        if (!haEnabled || state == null) {
            return;
        }

        try {
            Cache<URI, LRAState> cache = getCacheForState(state);
            cache.put(lraId, state);

            if (LRALogger.logger.isTraceEnabled()) {
                LRALogger.logger.tracef("Saved LRA %s to Infinispan cache (status: %s)",
                        lraId, state.getStatus());
            }
        } catch (Exception e) {
            LRALogger.logger.warnf(e, "Failed to save LRA %s to Infinispan", lraId);
        }
    }

    /**
     * Loads an LRA from Infinispan.
     * Checks all caches (active, recovering, failed).
     *
     * @param lraId the LRA ID
     * @return the LRA state, or null if not found
     */
    public LRAState loadLRA(URI lraId) {
        if (!haEnabled) {
            return null;
        }

        try {
            // Try active cache first
            LRAState state = getActiveLRACache().get(lraId);
            if (state != null) {
                return state;
            }

            // Try recovering cache
            state = getRecoveringLRACache().get(lraId);
            if (state != null) {
                return state;
            }

            // Try failed cache
            state = getFailedLRACache().get(lraId);
            return state;

        } catch (Exception e) {
            LRALogger.logger.warnf(e, "Failed to load LRA %s from Infinispan", lraId);
            return null;
        }
    }

    /**
     * Removes an LRA from all caches.
     *
     * @param lraId the LRA ID
     */
    public void removeLRA(URI lraId) {
        if (!haEnabled) {
            return;
        }

        try {
            getActiveLRACache().remove(lraId);
            getRecoveringLRACache().remove(lraId);
            getFailedLRACache().remove(lraId);

            if (LRALogger.logger.isTraceEnabled()) {
                LRALogger.logger.tracef("Removed LRA %s from Infinispan", lraId);
            }
        } catch (Exception e) {
            LRALogger.logger.warnf(e, "Failed to remove LRA %s from Infinispan", lraId);
        }
    }

    /**
     * Moves an LRA to the recovering cache (atomic operation).
     * Removes from active cache and adds to recovering cache.
     *
     * @param lraId the LRA ID
     * @param state the updated LRA state (should be Closing or Cancelling)
     */
    public void moveToRecovering(URI lraId, LRAState state) {
        if (!haEnabled) {
            return;
        }

        try {
            // Remove from active cache
            getActiveLRACache().remove(lraId);

            // Add to recovering cache
            getRecoveringLRACache().put(lraId, state);

            if (LRALogger.logger.isTraceEnabled()) {
                LRALogger.logger.tracef("Moved LRA %s to recovering cache", lraId);
            }
        } catch (Exception e) {
            LRALogger.logger.warnf(e, "Failed to move LRA %s to recovering cache", lraId);
        }
    }

    /**
     * Moves an LRA to the failed cache (atomic operation).
     * Removes from other caches and adds to failed cache.
     *
     * @param lraId the LRA ID
     */
    public void moveToFailed(URI lraId) {
        if (!haEnabled) {
            return;
        }

        try {
            // Load current state
            LRAState state = loadLRA(lraId);
            if (state == null) {
                LRALogger.logger.warnf("Cannot move LRA %s to failed cache - state not found", lraId);
                return;
            }

            // Remove from active and recovering caches
            getActiveLRACache().remove(lraId);
            getRecoveringLRACache().remove(lraId);

            // Add to failed cache
            getFailedLRACache().put(lraId, state);

            if (LRALogger.logger.isTraceEnabled()) {
                LRALogger.logger.tracef("Moved LRA %s to failed cache", lraId);
            }
        } catch (Exception e) {
            LRALogger.logger.warnf(e, "Failed to move LRA %s to failed cache", lraId);
        }
    }

    /**
     * Checks if HA mode is enabled.
     *
     * @return true if HA is enabled
     */
    public boolean isHaEnabled() {
        return haEnabled;
    }

    /**
     * Checks if the cache is available for operations.
     * Returns false if in minority partition (DEGRADED_MODE).
     *
     * This leverages Infinispan's built-in partition handling:
     * - AVAILABLE: Normal operation
     * - DEGRADED_MODE: Minority partition - denies read/write operations
     *
     * @return true if cache is available for operations
     */
    public boolean isAvailable() {
        if (!haEnabled || getActiveLRACache() == null) {
            return true; // Single-instance mode or not initialized
        }

        try {
            AvailabilityMode mode = getActiveLRACache().getAdvancedCache().getAvailability();

            if (mode == AvailabilityMode.DEGRADED_MODE) {
                if (LRALogger.logger.isDebugEnabled()) {
                    LRALogger.logger.debug(
                            "Cache in DEGRADED_MODE - node is in minority partition");
                }
                return false;
            }

            return true;
        } catch (Exception e) {
            LRALogger.logger.warnf(e, "Error checking cache availability, assuming unavailable");
            return false;
        }
    }

    /**
     * Gets the current availability mode of the cache.
     *
     * @return the availability mode, or null if not in HA mode
     */
    public AvailabilityMode getAvailabilityMode() {
        if (!haEnabled || getActiveLRACache() == null) {
            return null;
        }

        try {
            return getActiveLRACache().getAdvancedCache().getAvailability();
        } catch (Exception e) {
            LRALogger.logger.warnf(e, "Error getting cache availability mode");
            return null;
        }
    }

    /**
     * Gets the appropriate cache for an LRA based on its status.
     *
     * @param state the LRA state
     * @return the cache to use
     */
    private Cache<URI, LRAState> getCacheForState(LRAState state) {
        switch (state.getStatus()) {
            case Active:
                return getActiveLRACache();
            case Closing:
            case Cancelling:
                return getRecoveringLRACache();
            case FailedToClose:
            case FailedToCancel:
                return getFailedLRACache();
            case Closed:
            case Cancelled:
                // Finished LRAs can be in recovering cache if they have pending actions
                // Otherwise they should be removed
                return getRecoveringLRACache();
            default:
                return getActiveLRACache();
        }
    }

    /**
     * Gets the active LRA cache.
     *
     * @return the active LRA cache
     */
    public Cache<URI, LRAState> getActiveLRACache() {
        return activeLRACache;
    }

    /**
     * Gets the recovering LRA cache.
     *
     * @return the recovering LRA cache
     */
    public Cache<URI, LRAState> getRecoveringLRACache() {
        return recoveringLRACache;
    }

    /**
     * Gets the failed LRA cache.
     *
     * @return the failed LRA cache
     */
    public Cache<URI, LRAState> getFailedLRACache() {
        return failedLRACache;
    }
}
