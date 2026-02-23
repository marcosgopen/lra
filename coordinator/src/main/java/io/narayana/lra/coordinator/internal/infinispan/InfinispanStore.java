/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.internal.infinispan;

import io.narayana.lra.coordinator.domain.model.LRAState;
import io.narayana.lra.coordinator.internal.LRAStore;
import io.narayana.lra.logging.LRALogger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
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
public class InfinispanStore implements LRAStore {

    @Inject
    @Named("activeLRACache")
    private Cache<String, LRAState> activeLRACache;

    @Inject
    @Named("recoveringLRACache")
    private Cache<String, LRAState> recoveringLRACache;

    @Inject
    @Named("failedLRACache")
    private Cache<String, LRAState> failedLRACache;

    private volatile Boolean haEnabled = null;

    /**
     * Initializes the store and checks if HA mode is enabled.
     * Can be called explicitly or triggered lazily via {@link #isHaEnabled()}.
     */
    public void initialize() {
        String haEnabledProp = System.getProperty("lra.coordinator.ha.enabled", "false");
        boolean enabled = "true".equalsIgnoreCase(haEnabledProp);

        if (enabled) {
            if (getActiveLRACache() == null || getRecoveringLRACache() == null || getFailedLRACache() == null) {
                LRALogger.logger.warn("HA mode enabled but Infinispan caches not available, disabling HA");
                enabled = false;
            } else {
                LRALogger.logger.info("InfinispanStore initialized in HA mode");
            }
        } else {
            LRALogger.logger.debug("InfinispanStore initialized in single-instance mode");
        }

        this.haEnabled = enabled;
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
            Cache<String, LRAState> cache = getCacheForState(state);
            cache.put(lraId.toString(), state);

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
            String key = lraId.toString();

            // Try active cache first
            LRAState state = getActiveLRACache().get(key);
            if (state != null) {
                return state;
            }

            // Try recovering cache
            state = getRecoveringLRACache().get(key);
            if (state != null) {
                return state;
            }

            // Try failed cache
            state = getFailedLRACache().get(key);
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
            String key = lraId.toString();
            getActiveLRACache().remove(key);
            getRecoveringLRACache().remove(key);
            getFailedLRACache().remove(key);

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
            String key = lraId.toString();

            // Remove from active cache
            getActiveLRACache().remove(key);

            // Add to recovering cache
            getRecoveringLRACache().put(key, state);

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
            String key = lraId.toString();

            // Load current state
            LRAState state = loadLRA(lraId);
            if (state == null) {
                LRALogger.logger.warnf("Cannot move LRA %s to failed cache - state not found", lraId);
                return;
            }

            // Remove from active and recovering caches
            getActiveLRACache().remove(key);
            getRecoveringLRACache().remove(key);

            // Add to failed cache
            getFailedLRACache().put(key, state);

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
        if (haEnabled == null) {
            initialize();
        }
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
    private Cache<String, LRAState> getCacheForState(LRAState state) {
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
    public Cache<String, LRAState> getActiveLRACache() {
        return activeLRACache;
    }

    /**
     * Gets the recovering LRA cache.
     *
     * @return the recovering LRA cache
     */
    public Cache<String, LRAState> getRecoveringLRACache() {
        return recoveringLRACache;
    }

    /**
     * Gets the failed LRA cache.
     *
     * @return the failed LRA cache
     */
    public Cache<String, LRAState> getFailedLRACache() {
        return failedLRACache;
    }

    @Override
    public Map<String, LRAState> getAllActiveLRAs() {
        if (!haEnabled || getActiveLRACache() == null) {
            return Collections.emptyMap();
        }

        try {
            // Return the cache as a map - Infinispan Cache extends Map
            return getActiveLRACache();
        } catch (Exception e) {
            LRALogger.logger.warnf(e, "Failed to retrieve all active LRAs");
            return Collections.emptyMap();
        }
    }

    @Override
    public Collection<LRAState> getAllRecoveringLRAs() {
        if (!haEnabled || getRecoveringLRACache() == null) {
            return Collections.emptyList();
        }

        try {
            return getRecoveringLRACache().values();
        } catch (Exception e) {
            LRALogger.logger.warnf(e, "Failed to retrieve all recovering LRAs");
            return Collections.emptyList();
        }
    }

    @Override
    public Collection<LRAState> getAllFailedLRAs() {
        if (!haEnabled || getFailedLRACache() == null) {
            return Collections.emptyList();
        }

        try {
            return getFailedLRACache().values();
        } catch (Exception e) {
            LRALogger.logger.warnf(e, "Failed to retrieve all failed LRAs");
            return Collections.emptyList();
        }
    }
}
