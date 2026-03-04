/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.infinispan;

import io.narayana.lra.coordinator.domain.model.LRAState;
import io.narayana.lra.coordinator.internal.LRAStore;
import io.narayana.lra.coordinator.internal.StaleStateException;
import io.narayana.lra.logging.LRALogger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import org.infinispan.Cache;

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
    private Instance<Cache<String, LRAState>> activeLRACacheInstance;

    @Inject
    @Named("recoveringLRACache")
    private Instance<Cache<String, LRAState>> recoveringLRACacheInstance;

    @Inject
    @Named("failedLRACache")
    private Instance<Cache<String, LRAState>> failedLRACacheInstance;

    // Resolved lazily from Instance; null when HA is disabled or caches unavailable
    private Cache<String, LRAState> activeLRACache;
    private Cache<String, LRAState> recoveringLRACache;
    private Cache<String, LRAState> failedLRACache;

    private volatile boolean haEnabled = false;
    private volatile boolean haInitialized = false;

    /**
     * Initializes the store and checks if HA mode is enabled.
     * Can be called explicitly or triggered lazily via {@link #isHaEnabled()}.
     */
    public void initialize() {
        String haEnabledProp = System.getProperty("lra.coordinator.ha.enabled", "false");
        boolean enabled = "true".equalsIgnoreCase(haEnabledProp);

        if (enabled) {
            // Resolve caches: prefer already-set values (e.g. test subclass overrides),
            // then try CDI Instance resolution (tolerates unavailable producers)
            if (activeLRACache == null) {
                activeLRACache = resolveCache(activeLRACacheInstance);
            }
            if (recoveringLRACache == null) {
                recoveringLRACache = resolveCache(recoveringLRACacheInstance);
            }
            if (failedLRACache == null) {
                failedLRACache = resolveCache(failedLRACacheInstance);
            }

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
        this.haInitialized = true;
    }

    /**
     * Safely resolves a cache from a CDI Instance, returning null if unavailable.
     */
    private Cache<String, LRAState> resolveCache(Instance<Cache<String, LRAState>> instance) {
        if (instance == null || instance.isUnsatisfied()) {
            return null;
        }
        try {
            return instance.get();
        } catch (Exception e) {
            LRALogger.logger.debugf("Cache instance not available: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Saves LRA state with compare-and-swap semantics.
     * For new LRAs (expectedVersion == 0), uses putIfAbsent.
     * For existing LRAs, uses replace with version check.
     *
     * @param lraId the LRA ID
     * @param state the LRA state to save
     * @param expectedVersion the version the caller expects
     * @return the saved LRAState with incremented version
     * @throws StaleStateException if version conflict detected
     */
    @Override
    public LRAState saveOrFail(URI lraId, LRAState state, long expectedVersion) {
        if (!haEnabled || state == null) {
            return state;
        }

        String key = lraId.toString();
        long newVersion = expectedVersion + 1;
        InfinispanLRAState newState = toInfinispanState(state).withVersion(newVersion);
        Cache<String, LRAState> targetCache = getCacheForState(newState);

        if (expectedVersion == 0) {
            // New LRA — use putIfAbsent
            LRAState existing = targetCache.putIfAbsent(key, newState);
            if (existing != null) {
                throw new StaleStateException(lraId, expectedVersion, existing.getVersion());
            }
        } else {
            // Existing LRA — load current value, verify version, then CAS
            LRAState currentValue = loadLRA(lraId);
            if (currentValue == null) {
                // Entry was removed between caller's read and this write;
                // treat as new entry
                LRAState existing = targetCache.putIfAbsent(key, newState);
                if (existing != null) {
                    throw new StaleStateException(lraId, expectedVersion, existing.getVersion());
                }
            } else {
                if (currentValue.getVersion() != expectedVersion) {
                    throw new StaleStateException(lraId, expectedVersion, currentValue.getVersion());
                }
                // Determine which cache currently holds the entry
                Cache<String, LRAState> sourceCache = findCacheContaining(key);
                if (sourceCache == targetCache) {
                    // Same cache — atomic replace
                    boolean replaced = targetCache.replace(key, currentValue, newState);
                    if (!replaced) {
                        LRAState actual = loadLRA(lraId);
                        throw new StaleStateException(lraId, expectedVersion,
                                actual != null ? actual.getVersion() : -1);
                    }
                } else {
                    // Cross-cache move: CAS-remove from source to claim ownership,
                    // then write to destination. If CAS-remove fails, another node
                    // already modified/moved this entry.
                    if (sourceCache != null) {
                        boolean removed = sourceCache.remove(key, currentValue);
                        if (!removed) {
                            LRAState actual = loadLRA(lraId);
                            throw new StaleStateException(lraId, expectedVersion,
                                    actual != null ? actual.getVersion() : -1);
                        }
                    }
                    targetCache.put(key, newState);
                }
            }
        }

        // Clean stale entries from other caches
        removeFromOtherCaches(key, targetCache);

        if (LRALogger.logger.isTraceEnabled()) {
            LRALogger.logger.tracef("Saved LRA %s to Infinispan cache (status: %s, version: %d)",
                    lraId, state.getStatus(), newVersion);
        }

        return newState;
    }

    /**
     * Converts any {@link LRAState} to {@link InfinispanLRAState}.
     * Returns the instance as-is if it is already an InfinispanLRAState.
     */
    private InfinispanLRAState toInfinispanState(LRAState state) {
        if (state instanceof InfinispanLRAState) {
            return (InfinispanLRAState) state;
        }
        return InfinispanLRAState.from(state);
    }

    /**
     * Finds which cache currently holds the entry for the given key.
     *
     * @param key the cache key
     * @return the cache containing the key, or null if not found
     */
    private Cache<String, LRAState> findCacheContaining(String key) {
        if (getActiveLRACache().containsKey(key))
            return getActiveLRACache();
        if (getRecoveringLRACache().containsKey(key))
            return getRecoveringLRACache();
        if (getFailedLRACache().containsKey(key))
            return getFailedLRACache();
        return null;
    }

    /**
     * Removes an LRA entry from all caches except the specified target cache.
     * This ensures that status transitions via saveLRA() don't leave stale
     * entries in the previous cache.
     *
     * @param key the cache key (LRA ID as string)
     * @param targetCache the cache being written to (will not be touched)
     */
    private void removeFromOtherCaches(String key, Cache<String, LRAState> targetCache) {
        Cache<String, LRAState> active = getActiveLRACache();
        Cache<String, LRAState> recovering = getRecoveringLRACache();
        Cache<String, LRAState> failed = getFailedLRACache();

        if (targetCache != active) {
            active.remove(key);
        }
        if (targetCache != recovering) {
            recovering.remove(key);
        }
        if (targetCache != failed) {
            failed.remove(key);
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
     * Moves an LRA to the recovering cache with CAS semantics.
     *
     * @param lraId the LRA ID
     * @param state the updated LRA state (should be Closing or Cancelling)
     * @param expectedVersion the version the caller expects
     * @return true if the move succeeded, false if another node already moved it
     */
    @Override
    public boolean moveToRecovering(URI lraId, LRAState state, long expectedVersion) {
        try {
            saveOrFail(lraId, state, expectedVersion);
            return true;
        } catch (StaleStateException e) {
            if (LRALogger.logger.isDebugEnabled()) {
                LRALogger.logger.debugf("CAS conflict moving LRA %s to recovering: %s",
                        lraId, e.getMessage());
            }
            return false;
        }
    }

    /**
     * Moves an LRA to the failed cache.
     * Loads the current state, then re-saves it to the failed cache
     * while cleaning up stale entries from other caches.
     *
     * @param lraId the LRA ID
     */
    public void moveToFailed(URI lraId) {
        if (!haEnabled) {
            return;
        }

        try {
            // Load current state from whichever cache holds it
            LRAState state = loadLRA(lraId);
            if (state == null) {
                LRALogger.logger.warnf("Cannot move LRA %s to failed cache - state not found", lraId);
                return;
            }

            String key = lraId.toString();
            InfinispanLRAState ispnState = toInfinispanState(state);

            // Put into failed cache FIRST, then remove stale entries.
            // We can't delegate to saveLRA() here because the loaded state's
            // status may not be FailedToClose/FailedToCancel yet — the caller
            // is declaring this LRA as failed regardless of its current status.
            getFailedLRACache().put(key, ispnState);
            removeFromOtherCaches(key, getFailedLRACache());

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
        if (!haInitialized) {
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
        if (!isHaEnabled() || getActiveLRACache() == null) {
            return true; // Single-instance mode or not initialized
        }

        try {
            // Use string comparison to avoid class loading issues with AvailabilityMode
            // in WildFly deployments where org.infinispan.partitionhandling may not be exported
            String mode = getActiveLRACache().getAdvancedCache().getAvailability().name();

            if ("DEGRADED_MODE".equals(mode)) {
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
     * Gets the current availability mode of the cache as a string.
     *
     * @return the availability mode name (e.g. "AVAILABLE", "DEGRADED_MODE"), or null if not in HA mode
     */
    public String getAvailabilityMode() {
        if (!haEnabled || getActiveLRACache() == null) {
            return null;
        }

        try {
            return getActiveLRACache().getAdvancedCache().getAvailability().name();
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
            return Collections.unmodifiableMap(getActiveLRACache());
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
            return Collections.unmodifiableCollection(getRecoveringLRACache().values());
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
            return Collections.unmodifiableCollection(getFailedLRACache().values());
        } catch (Exception e) {
            LRALogger.logger.warnf(e, "Failed to retrieve all failed LRAs");
            return Collections.emptyList();
        }
    }
}
