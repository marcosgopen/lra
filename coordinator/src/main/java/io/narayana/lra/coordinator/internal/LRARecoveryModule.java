/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.internal;

import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.common.recoveryPropertyManager;
import com.arjuna.ats.arjuna.coordinator.ActionStatus;
import com.arjuna.ats.arjuna.exceptions.ObjectStoreException;
import com.arjuna.ats.arjuna.objectstore.RecoveryStore;
import com.arjuna.ats.arjuna.objectstore.StateStatus;
import com.arjuna.ats.arjuna.objectstore.StoreManager;
import com.arjuna.ats.arjuna.recovery.RecoveryManager;
import com.arjuna.ats.arjuna.recovery.RecoveryModule;
import com.arjuna.ats.arjuna.recovery.TransactionStatusConnectionManager;
import com.arjuna.ats.arjuna.state.InputObjectState;
import com.arjuna.ats.arjuna.state.OutputObjectState;
import io.narayana.lra.coordinator.domain.model.FailedLongRunningAction;
import io.narayana.lra.coordinator.domain.model.LongRunningAction;
import io.narayana.lra.coordinator.domain.service.LRAService;
import io.narayana.lra.logging.LRALogger;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.eclipse.microprofile.lra.annotation.LRAStatus;

public class LRARecoveryModule implements RecoveryModule,
        ClusterCoordinator.CoordinatorChangeListener {

    // HA components
    private InfinispanStore infinispanStore;
    private DistributedLockManager distributedLockManager;
    private ClusterCoordinator clusterCoordinator;
    private volatile boolean isRecoveryLeader = false;
    private volatile boolean haEnabled = false;

    public LRARecoveryModule() {
        service = new LRAService();

        if (_recoveryStore == null) {
            _recoveryStore = StoreManager.getRecoveryStore();
        }

        _transactionStatusConnectionMgr = new TransactionStatusConnectionManager();
        Implementations.install();

        // Initialize HA components if available
        initializeHAComponents();
    }

    /**
     * Initializes HA components if they are available via CDI.
     * This is called from the constructor after the service is created.
     */
    private void initializeHAComponents() {
        try {
            // Try to get CDI container
            jakarta.enterprise.inject.spi.CDI<Object> cdi = jakarta.enterprise.inject.spi.CDI.current();

            // Try to get HA components from CDI
            this.infinispanStore = tryGetBean(cdi, InfinispanStore.class);
            this.distributedLockManager = tryGetBean(cdi, DistributedLockManager.class);
            this.clusterCoordinator = tryGetBean(cdi, ClusterCoordinator.class);

            // If we got at least the InfinispanStore, initialize HA mode
            if (infinispanStore != null) {
                this.haEnabled = infinispanStore.isHaEnabled();
                service.initializeHA(infinispanStore, distributedLockManager, clusterCoordinator);

                // Register for cluster coordinator change notifications
                if (clusterCoordinator != null && clusterCoordinator.isInitialized()) {
                    clusterCoordinator.addCoordinatorChangeListener(this);
                    LRALogger.logger.info("LRARecoveryModule registered for cluster coordinator notifications");
                }

                LRALogger.logger.info("LRARecoveryModule initialized with HA components");
            } else {
                LRALogger.logger.debug("HA components not available, running in single-instance mode");
            }
        } catch (IllegalStateException e) {
            // CDI not available - this is normal in non-CDI environments
            LRALogger.logger.debug("CDI not available, HA components will not be initialized");
        } catch (Exception e) {
            LRALogger.logger.warn("Failed to initialize HA components", e);
        }
    }

    /**
     * Safely tries to get a CDI bean, returning null if not available.
     */
    private <T> T tryGetBean(jakarta.enterprise.inject.spi.CDI<Object> cdi, Class<T> beanClass) {
        try {
            return cdi.select(beanClass).get();
        } catch (Exception e) {
            LRALogger.logger.debugf("Bean %s not available: %s", beanClass.getSimpleName(), e.getMessage());
            return null;
        }
    }

    public static LRAService getService() {
        return getInstance().service; // this call triggers the creation of the LRARecoveryModule singleton which contains service
    }

    public static LRARecoveryModule getInstance() {
        if (lraRecoveryModule != null) {
            return lraRecoveryModule;
        }

        // see if periodic recovery already knows about this recovery module
        // note that this lookup code is re-entrant hence no synchronization:
        RecoveryManager.manager();

        for (RecoveryModule rm : recoveryPropertyManager.getRecoveryEnvironmentBean().getRecoveryModules()) {
            if (rm instanceof LRARecoveryModule) {
                lraRecoveryModule = (LRARecoveryModule) rm;

                return lraRecoveryModule;
            }
        }

        // When running in WildFly the jbossts-properties.xml config is overridden so we need to add the module directly
        // Until we have a WildFly subsystem for LRA register the recovery module manually:
        synchronized (LRARecoveryModule.class) {
            if (lraRecoveryModule == null) {
                lraRecoveryModule = new LRARecoveryModule();

                RecoveryManager.manager().addModule(lraRecoveryModule); // register it for periodic recovery
            }
        }

        return lraRecoveryModule;
    }

    /**
     * This is called periodically by the RecoveryManager
     */
    public void periodicWorkFirstPass() {
        if (LRALogger.logger.isTraceEnabled()) {
            LRALogger.logger.trace("LRARecoveryModule: first pass");
        }
    }

    /**
     * Periodic recovery pass - performs recovery of LRA transactions.
     * In HA mode, only the cluster coordinator performs recovery to avoid conflicts.
     */
    public void periodicWorkSecondPass() {
        if (LRALogger.logger.isTraceEnabled()) {
            LRALogger.logger.trace("LRARecoveryModule: second pass");
        }

        // In HA mode, only the cluster coordinator performs recovery
        if (haEnabled && !isRecoveryLeader) {
            if (LRALogger.logger.isTraceEnabled()) {
                LRALogger.logger.trace("LRARecoveryModule: skipping recovery (not the cluster coordinator)");
            }
            return;
        }

        // In HA mode, check if cache is available (not in minority partition)
        if (haEnabled && infinispanStore != null && !infinispanStore.isAvailable()) {
            if (LRALogger.logger.isDebugEnabled()) {
                LRALogger.logger.debug("LRARecoveryModule: skipping recovery (cache in DEGRADED_MODE - minority partition)");
            }
            return;
        }

        // Check for timed-out LRAs (HA mode only - safety net if coordinator fails before timeout)
        checkForTimedOutLRAs();

        // Recover pending transactions
        recoverTransactions();
    }

    // CoordinatorChangeListener implementation for JGroups coordinator election

    /**
     * Called when this node becomes the cluster coordinator.
     * Immediately triggers a recovery pass.
     */
    @Override
    public void onBecameCoordinator() {
        isRecoveryLeader = true;
        LRALogger.logger.info("LRARecoveryModule: This node became the cluster coordinator, starting immediate recovery");

        // Perform immediate recovery pass when becoming coordinator
        try {
            recoverTransactions();
        } catch (Exception e) {
            LRALogger.logger.warnf(e, "Error during immediate recovery after becoming coordinator");
        }
    }

    /**
     * Called when this node loses cluster coordinator status.
     * Stops performing recovery.
     */
    @Override
    public void onLostCoordinator() {
        isRecoveryLeader = false;
        LRALogger.logger.info("LRARecoveryModule: This node lost cluster coordinator status, stopping recovery");
    }

    /**
     * Recovers LRA transactions.
     * In HA mode, loads from Infinispan recovering cache.
     * In single-instance mode, loads from ObjectStore.
     */
    private synchronized void recoverTransactions() {
        if (haEnabled && infinispanStore != null) {
            recoverTransactionsFromInfinispan();
        } else {
            recoverTransactionsFromObjectStore();
        }
    }

    /**
     * Recovers LRAs from Infinispan (HA mode).
     * Uses distributed locks to coordinate recovery across cluster.
     */
    private void recoverTransactionsFromInfinispan() {
        if (LRALogger.logger.isDebugEnabled()) {
            LRALogger.logger.debug("LRARecoveryModule: recovering transactions from Infinispan");
        }

        try {
            // Get the recovering cache from Infinispan
            org.infinispan.manager.EmbeddedCacheManager cacheManager = infinispanStore instanceof InfinispanStore
                    ? getCacheManagerFromStore(infinispanStore)
                    : null;

            if (cacheManager == null) {
                LRALogger.logger.warn("Cache manager not available, falling back to ObjectStore recovery");
                recoverTransactionsFromObjectStore();
                return;
            }

            org.infinispan.Cache<URI, io.narayana.lra.coordinator.domain.model.LRAState> recoveringCache = cacheManager
                    .getCache(InfinispanConfiguration.RECOVERING_LRA_CACHE_NAME);

            if (recoveringCache == null) {
                LRALogger.logger.warn("Recovering cache not available");
                return;
            }

            int recoveryCount = 0;
            int lockedCount = 0;

            // Iterate over all recovering LRAs
            for (java.util.Map.Entry<URI, io.narayana.lra.coordinator.domain.model.LRAState> entry : recoveringCache
                    .entrySet()) {
                URI lraId = entry.getKey();

                // Try to acquire distributed lock (with short timeout to avoid blocking)
                DistributedLockManager.LockHandle lockHandle = null;
                if (distributedLockManager != null) {
                    lockHandle = distributedLockManager.acquireLock(lraId, 100,
                            java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (lockHandle == null) {
                        // Another node is recovering this LRA
                        lockedCount++;
                        if (LRALogger.logger.isTraceEnabled()) {
                            LRALogger.logger.tracef(
                                    "LRARecoveryModule: Skipping LRA %s (locked by another node)", lraId);
                        }
                        continue;
                    }
                }

                try {
                    // Recover this LRA
                    doRecoverTransactionFromState(lraId, entry.getValue());
                    recoveryCount++;
                } catch (Exception e) {
                    LRALogger.logger.warnf(e, "Error recovering LRA %s from Infinispan", lraId);
                } finally {
                    // Release distributed lock
                    if (lockHandle != null) {
                        try {
                            lockHandle.release();
                        } catch (Exception e) {
                            LRALogger.logger.warnf(e, "Error releasing lock for LRA %s", lraId);
                        }
                    }
                }
            }

            if (LRALogger.logger.isDebugEnabled()) {
                LRALogger.logger.debugf(
                        "LRARecoveryModule: Recovered %d LRAs from Infinispan (%d skipped - locked by other nodes)",
                        recoveryCount, lockedCount);
            }

        } catch (Exception e) {
            LRALogger.logger.errorf(e, "Error during Infinispan recovery, falling back to ObjectStore");
            recoverTransactionsFromObjectStore();
        }
    }

    /**
     * Scans active LRAs for timeouts and initiates cancellation.
     * This ensures that LRAs timeout even if the creating coordinator fails.
     *
     * This is the safety net for timeout handling in HA mode:
     * - Normal path: Creating coordinator schedules local timeout (fast, best-effort)
     * - Safety net: Recovery coordinator detects expired LRAs during periodic scan
     *
     * Only runs in HA mode, as single-instance mode relies on local schedulers.
     */
    private void checkForTimedOutLRAs() {
        if (!haEnabled || infinispanStore == null) {
            return; // Single-instance mode uses local schedulers
        }

        try {
            // Get the active LRA cache
            org.infinispan.Cache<URI, io.narayana.lra.coordinator.domain.model.LRAState> activeCache = infinispanStore
                    .getActiveLRACache();

            if (activeCache == null) {
                if (LRALogger.logger.isDebugEnabled()) {
                    LRALogger.logger.debug("Active LRA cache not available for timeout checking");
                }
                return;
            }

            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            int timeoutCount = 0;
            int lockedCount = 0;

            // Scan all active LRAs for timeouts
            for (java.util.Map.Entry<URI, io.narayana.lra.coordinator.domain.model.LRAState> entry : activeCache.entrySet()) {
                URI lraId = entry.getKey();
                io.narayana.lra.coordinator.domain.model.LRAState state = entry.getValue();

                // Check if LRA has a timeout and it has expired
                if (state.getFinishTime() != null && now.isAfter(state.getFinishTime())) {

                    // Acquire distributed lock to prevent multiple coordinators
                    // from timing out the same LRA
                    DistributedLockManager.LockHandle lockHandle = null;
                    if (distributedLockManager != null) {
                        lockHandle = distributedLockManager.acquireLock(lraId, 100, TimeUnit.MILLISECONDS);
                        if (lockHandle == null) {
                            // Another node is handling this timeout
                            lockedCount++;
                            if (LRALogger.logger.isTraceEnabled()) {
                                LRALogger.logger.tracef(
                                        "LRARecoveryModule: Skipping timeout for LRA %s (locked by another node)",
                                        lraId);
                            }
                            continue;
                        }
                    }

                    try {
                        // Re-check timeout after acquiring lock (state may have changed)
                        state = infinispanStore.loadLRA(lraId);
                        if (state != null &&
                                state.getStatus() == LRAStatus.Active &&
                                state.getFinishTime() != null &&
                                now.isAfter(state.getFinishTime())) {

                            if (LRALogger.logger.isInfoEnabled()) {
                                LRALogger.logger.infof(
                                        "LRARecoveryModule: LRA %s has timed out (finishTime=%s, now=%s), initiating cancellation",
                                        lraId, state.getFinishTime(), now);
                            }

                            // Load the LRA and trigger cancellation
                            LongRunningAction lra = service.getTransaction(lraId);
                            if (lra != null) {
                                // Trigger cancellation (finishLRA handles state transition internally)
                                lra.finishLRA(true); // true = cancel
                                timeoutCount++;
                            } else {
                                LRALogger.logger.warnf(
                                        "LRARecoveryModule: Cannot load LRA %s for timeout processing", lraId);
                            }
                        }
                    } catch (Exception e) {
                        LRALogger.logger.warnf(e,
                                "Error timing out LRA %s", lraId);
                    } finally {
                        if (lockHandle != null) {
                            try {
                                lockHandle.release();
                            } catch (Exception e) {
                                LRALogger.logger.warnf(e, "Error releasing lock for LRA %s", lraId);
                            }
                        }
                    }
                }
            }

            if (timeoutCount > 0 || (LRALogger.logger.isDebugEnabled() && lockedCount > 0)) {
                LRALogger.logger.infof(
                        "LRARecoveryModule: Processed %d timed-out LRAs (%d skipped - locked by other nodes)",
                        timeoutCount, lockedCount);
            }

        } catch (Exception e) {
            LRALogger.logger.errorf(e, "Error checking for timed-out LRAs");
        }
    }

    /**
     * Recovers LRAs from ObjectStore (single-instance mode).
     * This is the original recovery logic.
     */
    private void recoverTransactionsFromObjectStore() {
        if (LRALogger.logger.isDebugEnabled()) {
            LRALogger.logger.debug("LRARecoveryModule: recovering transactions from ObjectStore");
        }

        // uids per transaction type
        InputObjectState aa_uids = new InputObjectState();

        if (getUids(_transactionType, aa_uids)) {
            processTransactionsStatus(processTransactions(aa_uids));
        }
    }

    /**
     * Helper method to get cache manager from InfinispanStore.
     * Uses reflection to access the InfinispanConfiguration.
     */
    private org.infinispan.manager.EmbeddedCacheManager getCacheManagerFromStore(InfinispanStore store) {
        try {
            // Try to get InfinispanConfiguration via CDI
            jakarta.enterprise.inject.spi.CDI<Object> cdi = jakarta.enterprise.inject.spi.CDI.current();
            InfinispanConfiguration config = tryGetBean(cdi, InfinispanConfiguration.class);
            if (config != null) {
                return config.cacheManager();
            }
        } catch (Exception e) {
            LRALogger.logger.debugf("Could not get cache manager via CDI: %s", e.getMessage());
        }
        return null;
    }

    /**
     * Recovers a single LRA from its LRAState (Infinispan recovery).
     */
    private void doRecoverTransactionFromState(URI lraId, io.narayana.lra.coordinator.domain.model.LRAState state) {
        try {
            // Extract UID from LRA ID
            String uidString = io.narayana.lra.LRAConstants.getLRAUid(lraId);
            com.arjuna.ats.arjuna.common.Uid recoverUid = new com.arjuna.ats.arjuna.common.Uid(uidString);

            // Get transaction status (may not be available in distributed environment)
            int theStatus = ActionStatus.COMMITTED; // Default assumption

            // Create RecoveringLRA from the state
            RecoveringLRA lra = new RecoveringLRA(service, recoverUid, theStatus);

            // Restore state from LRAState
            if (!lra.fromLRAState(state)) {
                LRALogger.logger.warnf("Failed to restore LRA %s from state", lraId);
                return;
            }

            LRAStatus lraStatus = lra.getLRAStatus();

            // Handle failed LRAs
            if (LRAStatus.FailedToCancel.equals(lraStatus) || LRAStatus.FailedToClose.equals(lraStatus)) {
                moveEntryToFailedLRAPath(lraId, state);
                return;
            }

            // Add to LRAService if not already known
            if (!service.hasTransaction(lra.getId())) {
                service.addTransaction(lra);
            }

            if (LRALogger.logger.isDebugEnabled()) {
                LRALogger.logger.debugf("LRARecoveryModule: recovering LRA %s, status: %s",
                        lraId, lraStatus);
            }

            // Replay phase 2 if necessary
            boolean inFlight = (lraStatus == LRAStatus.Active);
            if (!inFlight && lra.hasPendingActions()) {
                lra.replayPhase2();

                if (!lra.isRecovering()) {
                    service.finished(lra, false);
                }
            }

        } catch (Exception e) {
            LRALogger.logger.warnf(e, "Error recovering LRA %s", lraId);
        }
    }

    private void doRecoverTransaction(Uid recoverUid) {
        // Retrieve the transaction status from its original process // TODO remove because it is not needed
        int theStatus = _transactionStatusConnectionMgr.getTransactionStatus(_transactionType, recoverUid);

        try {
            RecoveringLRA lra = new RecoveringLRA(service, recoverUid, theStatus);
            boolean inFlight = (lra.getLRAStatus() == LRAStatus.Active);

            LRAStatus lraStatus = lra.getLRAStatus();
            if (LRAStatus.FailedToCancel.equals(lraStatus) || LRAStatus.FailedToClose.equals(lraStatus)) {
                moveEntryToFailedLRAPath(recoverUid);
                return;
            }

            if (!service.hasTransaction(lra.getId())) {
                // make sure LRAService knows about it
                service.addTransaction(lra);
            }

            if (LRALogger.logger.isDebugEnabled()) {
                LRALogger.logger.debug("LRARecoverModule: transaction type is " + _transactionType + " uid is " +
                        recoverUid.toString() + "\n Status is " + lraStatus +
                        " in flight is " + inFlight);
            }

            if (!inFlight && lra.hasPendingActions()) {
                lra.replayPhase2();

                if (!lra.isRecovering()) {
                    service.finished(lra, false);
                }
            }

        } catch (Exception e) {
            if (LRALogger.logger.isInfoEnabled()) {
                LRALogger.logger.infof(
                        "LRARecoverModule: Error '%s' while recovering LRA record %s",
                        e.getMessage(), recoverUid.fileStringForm());
            }
        }
    }

    /**
     * Moves an LRA entry to the failed LRA path.
     * Overloaded version that takes a Uid (for ObjectStore).
     */
    public boolean moveEntryToFailedLRAPath(final Uid failedUid) {
        String failedLRAType = FailedLongRunningAction.FAILED_LRA_TYPE;
        boolean moved = false;
        try {
            InputObjectState inputState = _recoveryStore.read_committed(failedUid, _transactionType);
            InputObjectState failedLRAUidState = _recoveryStore.read_committed(failedUid, failedLRAType);
            if (inputState != null) {
                if (failedLRAUidState != null) {
                    // Record already exists in failedLRARecord location, hence removing it from the LRARecord location
                    moved = true;
                    if (!_recoveryStore.remove_committed(failedUid, _transactionType)) {
                        LRALogger.i18nLogger.warn_UnableToRemoveDuplicateFailedLRAParticipantRecord(
                                failedUid.toString(), failedLRAType, _transactionType);
                        moved = false;
                    }
                    return moved;
                }

                if (_recoveryStore.write_committed(failedUid, failedLRAType, new OutputObjectState(inputState))) {
                    moved = _recoveryStore.remove_committed(failedUid, _transactionType);
                    if (moved) {
                        LRALogger.logger.infof("Failed lra record (Uid: %s) moved to new location type: %s", failedUid,
                                failedLRAType);
                    }
                }
            }
        } catch (ObjectStoreException e) {
            LRALogger.i18nLogger.warn_move_lra_record(failedUid.toString(), e.getMessage());
        }
        return moved;
    }

    /**
     * Moves an LRA entry to the failed state.
     * In HA mode, uses InfinispanStore.moveToFailed() which is atomic.
     * In single-instance mode, delegates to moveEntryToFailedLRAPath(Uid).
     */
    private boolean moveEntryToFailedLRAPath(URI lraId,
            io.narayana.lra.coordinator.domain.model.LRAState state) {
        if (haEnabled && infinispanStore != null) {
            // In HA mode, use InfinispanStore.moveToFailed() which is atomic
            try {
                infinispanStore.moveToFailed(lraId);
                LRALogger.logger.infof("Failed LRA %s moved to failed state in Infinispan", lraId);
                return true;
            } catch (Exception e) {
                LRALogger.logger.warnf(e, "Failed to move LRA %s to failed state in Infinispan", lraId);
                return false;
            }
        } else {
            // In single-instance mode, use ObjectStore
            String uidString = io.narayana.lra.LRAConstants.getLRAUid(lraId);
            Uid uid = new Uid(uidString);
            return moveEntryToFailedLRAPath(uid);
        }
    }

    private Collection<Uid> processTransactions(InputObjectState uids) {
        Collection<Uid> uidCollection = new ArrayList<>();

        if (LRALogger.logger.isDebugEnabled()) {
            LRALogger.logger.debugf("LRARecoverModule: processing transaction type %s", _transactionType);
        }

        Consumer<Uid> uidUnpacker = uidCollection::add;

        forEach(uids, uidUnpacker, _transactionType);

        return uidCollection;
    }

    private void processTransactionsStatus(Collection<Uid> uids) {
        // Process the collection of transaction Uids
        uids.forEach(uid -> {
            try {
                if (_recoveryStore.currentState(uid, _transactionType) != StateStatus.OS_UNKNOWN) {
                    doRecoverTransaction(uid);
                }
            } catch (ObjectStoreException e) {
                if (LRALogger.logger.isTraceEnabled()) {
                    LRALogger.logger.tracef(e,
                            "LRARecoverModule: Object store exception '%s' while reading the current state of LRA record %s:",
                            e.getMessage(), uid.fileStringForm());
                } else if (LRALogger.logger.isInfoEnabled()) {
                    LRALogger.logger.infof(
                            "LRARecoverModule: Object store exception '%s' while reading the current state of LRA record %s",
                            e.getMessage(), uid.fileStringForm());
                }
            }
        });
    }

    /**
     * remove an LRA log record
     *
     * @param lraUid LRA id that will be removed from the log record
     * @return false if record isn't in the store or there was an error removing it
     */
    public boolean removeCommitted(Uid lraUid) {
        try {
            return _recoveryStore.remove_committed(lraUid, _transactionType);
        } catch (ObjectStoreException e) {
            if (LRALogger.logger.isTraceEnabled()) {
                LRALogger.logger.tracef(e,
                        "LRARecoveryModule: Object store exception '%s' while removing LRA record %s",
                        e.getMessage(), lraUid.fileStringForm());
            } else if (LRALogger.logger.isInfoEnabled()) {
                LRALogger.logger.infof(
                        "LRARecoveryModule: Object store exception '%s' while removing LRA record %s",
                        e.getMessage(), lraUid.fileStringForm());
            }
        }

        return false;
    }

    public void recover() {
        recoverTransactions();
    }

    /**
     * Checks if this node is the current recovery leader.
     * In HA mode, only the Raft leader performs recovery.
     * In single-instance mode, always returns true.
     *
     * @return true if this node is performing recovery
     */
    public boolean isRecoveryLeader() {
        return !haEnabled || isRecoveryLeader;
    }

    /**
     * Checks if HA mode is enabled.
     *
     * @return true if HA is enabled
     */
    public boolean isHaEnabled() {
        return haEnabled;
    }

    public void getFailedLRAs(Map<URI, LongRunningAction> lras) {
        InputObjectState aa_uids = new InputObjectState();
        Consumer<Uid> failedLRACreator = uid -> {
            FailedLongRunningAction lra = new FailedLongRunningAction(service, new Uid(uid));
            lra.activate();

            LRAStatus status = lra.getLRAStatus();
            if (LRAStatus.FailedToCancel.equals(status) || LRAStatus.FailedToClose.equals(status)) {
                lras.put(lra.getId(), lra);
            }
        };

        if (getUids(FailedLongRunningAction.FAILED_LRA_TYPE, aa_uids)) {
            forEach(aa_uids, failedLRACreator, FailedLongRunningAction.FAILED_LRA_TYPE);
        }
    }

    private boolean getUids(final String type, InputObjectState aa_uids) {
        synchronized (this) {
            try {
                return _recoveryStore.allObjUids(type, aa_uids);
            } catch (ObjectStoreException e) {
                if (LRALogger.logger.isTraceEnabled()) {
                    LRALogger.logger.tracef(e,
                            "LRARecoverModule: Object store exception %s while unpacking records of type %s",
                            e.getMessage(), type);
                } else if (LRALogger.logger.isInfoEnabled()) {
                    LRALogger.logger.infof(
                            "LRARecoverModule: Object store exception %s while unpacking records of type %s",
                            e.getMessage(), type);
                }

                return false;
            }
        }
    }

    /**
     * Iterate over a collection of Uids
     *
     * @param uids the uids to iterate over
     * @param consumer the consumer that should be called for each Uid
     * @param transactionType within the Object Store for LRAs
     */
    // This method could be moved to an ArjunaCore class (such as UidHelper) if its useful,
    // in which case make the method returns a boolean:
    //     * @return false if there was an error processing the collection of uids
    // The @param transactionType is only used for logging purpose
    private void forEach(InputObjectState uids, Consumer<Uid> consumer, final String transactionType) {
        do {
            try {
                Uid uid = new Uid(uids.unpackBytes());

                if (uid.equals(Uid.nullUid())) {
                    return;
                }

                consumer.accept(uid);
            } catch (IOException e) {
                if (LRALogger.logger.isTraceEnabled()) {
                    LRALogger.logger.tracef(e,
                            "LRARecoverModule: Object store exception %s while unpacking a record of type %s",
                            e.getMessage(), transactionType);
                } else if (LRALogger.logger.isInfoEnabled()) {
                    LRALogger.logger.infof(
                            "LRARecoverModule: Object store exception %s while unpacking a record of type: %s",
                            e.getMessage(), transactionType);
                }

                return;
            }

        } while (true);
    }

    private final LRAService service;

    // 'type' within the Object Store for LRAs.
    private final String _transactionType = LongRunningAction.getType();

    // Reference to the Object Store.
    private static RecoveryStore _recoveryStore = null;

    // This object manages the interface to all TransactionStatusManager
    // processes(JVMs) on this system/node.
    private final TransactionStatusConnectionManager _transactionStatusConnectionMgr;

    private static LRARecoveryModule lraRecoveryModule;
}
