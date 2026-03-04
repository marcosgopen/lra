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
import io.narayana.lra.LRAConstants;
import io.narayana.lra.coordinator.domain.model.FailedLongRunningAction;
import io.narayana.lra.coordinator.domain.model.LRAState;
import io.narayana.lra.coordinator.domain.model.LongRunningAction;
import io.narayana.lra.coordinator.domain.service.LRAService;
import io.narayana.lra.logging.LRALogger;
import jakarta.enterprise.inject.spi.CDI;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;
import org.eclipse.microprofile.lra.annotation.LRAStatus;

public class LRARecoveryModule implements RecoveryModule,
        ClusterCoordinationService.CoordinatorChangeListener {

    // HA components
    private LRAStore lraStore;
    private ClusterCoordinationService clusterCoordinator;
    private volatile boolean isRecoveryLeader = false;
    private volatile boolean haEnabled = false;
    private volatile boolean haInitAttempted = false;

    public LRARecoveryModule() {
        service = new LRAService();

        if (_recoveryStore == null) {
            _recoveryStore = StoreManager.getRecoveryStore();
        }

        _transactionStatusConnectionMgr = new TransactionStatusConnectionManager();
        Implementations.install();

        // HA initialization is deferred to ensureHAInitialized() because this
        // constructor runs on a ServerService thread during deployment, before
        // CDI has finished processing the WAR's bean archives. Attempting
        // CDI.current().select(LRAStore.class) at this point fails with an
        // UnsatisfiedResolutionException because the InfinispanStore bean
        // (in WEB-INF/lib/coordinator-ha-infinispan.jar) hasn't been
        // discovered yet.
    }

    /**
     * Ensures HA components are initialized exactly once. This is called
     * lazily (not from the constructor) because the constructor runs on a
     * WildFly ServerService thread during deployment, before CDI has
     * finished discovering beans in the WAR's library JARs.
     */
    private void ensureHAInitialized() {
        if (!"true".equalsIgnoreCase(System.getProperty("lra.coordinator.ha.enabled", "false"))) {
            return;
        }
        if (haInitAttempted) {
            return;
        }
        synchronized (this) {
            if (haInitAttempted) {
                return;
            }
            // initializeHAComponents sets haInitAttempted = true only when
            // initialization succeeds. If CDI beans are not yet available
            // (e.g. called during deployment before bean discovery completes),
            // haInitAttempted stays false so the next call retries.
            initializeHAComponents();
        }
    }

    /**
     * Initializes HA components by looking up CDI beans (LRAStore,
     * ClusterCoordinationService). Only called when HA mode is enabled — the
     * caller ({@link #ensureHAInitialized()}) guards on the system property.
     *
     * <p>
     * Sets {@code haInitAttempted = true} only on success. If CDI beans are
     * not yet available (e.g. during early deployment), returns without
     * setting the flag so that the next call retries.
     * </p>
     */
    private void initializeHAComponents() {
        try {
            // Try to get CDI container
            CDI<Object> cdi = CDI.current();

            // Try to get HA components from CDI
            this.lraStore = tryGetBean(cdi, LRAStore.class);
            this.clusterCoordinator = tryGetBean(cdi, ClusterCoordinationService.class);

            if (lraStore == null) {
                // Bean not available yet — this is expected when called during
                // deployment before CDI has finished processing bean archives.
                // Leave haInitAttempted false so the next call retries.
                LRALogger.logger.warn("HA mode is enabled (lra.coordinator.ha.enabled=true) "
                        + "but LRAStore bean is not available yet. "
                        + "Will retry on next access.");
                return;
            }

            this.haEnabled = true;
            service.initializeHA(lraStore, clusterCoordinator);

            // Register for cluster coordinator change notifications
            if (clusterCoordinator != null && clusterCoordinator.isInitialized()) {
                clusterCoordinator.addCoordinatorChangeListener(this);
                // Seed isRecoveryLeader from the coordinator's current state so
                // recovery works immediately without waiting for a view change event
                this.isRecoveryLeader = clusterCoordinator.isCoordinator();
                LRALogger.logger.info("LRARecoveryModule registered for cluster coordinator notifications");
            } else {
                // No cluster coordinator available (single-node HA, CDI lookup
                // failure, or JGroups not configured). This node must assume
                // recovery leadership, otherwise recovery never runs because
                // onBecameCoordinator() is never called.
                this.isRecoveryLeader = true;
                LRALogger.logger.info(
                        "LRARecoveryModule: no cluster coordinator available, assuming recovery leadership");
            }

            haInitAttempted = true;
            LRALogger.logger.info("LRARecoveryModule initialized with HA components");
        } catch (IllegalStateException e) {
            // CDI not available yet — leave haInitAttempted false to retry later
            LRALogger.logger.warn("HA mode is enabled but CDI is not available yet. "
                    + "Will retry on next access.");
        } catch (Exception e) {
            // Unexpected failure — leave haInitAttempted false to retry later
            LRALogger.logger.warn("HA mode is enabled but initialization failed, will retry on next access", e);
        }
    }

    /**
     * Safely tries to get a CDI bean, returning null if not available.
     */
    private <T> T tryGetBean(CDI<Object> cdi, Class<T> beanClass) {
        try {
            return cdi.select(beanClass).get();
        } catch (Exception e) {
            LRALogger.logger.debugf("Bean %s not available: %s", beanClass.getSimpleName(), e.getMessage());
            return null;
        }
    }

    public static LRAService getService() {
        LRARecoveryModule instance = getInstance();
        instance.ensureHAInitialized();
        return instance.service;
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
        ensureHAInitialized();

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
        if (haEnabled && lraStore != null && !lraStore.isAvailable()) {
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
     * In HA mode, loads from distributed store.
     * In single-instance mode, loads from ObjectStore.
     */
    private synchronized void recoverTransactions() {
        if (haEnabled && lraStore != null) {
            recoverTransactionsFromDistributedStore();
        } else {
            recoverTransactionsFromObjectStore();
        }
    }

    /**
     * Recovers LRAs from distributed store (HA mode).
     * Uses CAS (compare-and-swap) to coordinate recovery across cluster.
     * Each node "claims" an LRA by CAS-writing with an incremented version;
     * if another node already claimed it, the CAS fails and we skip it.
     */
    private void recoverTransactionsFromDistributedStore() {
        if (LRALogger.logger.isDebugEnabled()) {
            LRALogger.logger.debug("LRARecoveryModule: recovering transactions from distributed store");
        }

        try {
            // Get all recovering LRAs from the store
            Collection<io.narayana.lra.coordinator.domain.model.LRAState> recoveringLRAs = lraStore.getAllRecoveringLRAs();

            if (recoveringLRAs == null || recoveringLRAs.isEmpty()) {
                if (LRALogger.logger.isTraceEnabled()) {
                    LRALogger.logger.trace("No recovering LRAs found in distributed store");
                }
                return;
            }

            int recoveryCount = 0;
            int skippedCount = 0;

            for (io.narayana.lra.coordinator.domain.model.LRAState state : recoveringLRAs) {
                URI lraId = state.getId();

                try {
                    // "Claim" this LRA by CAS-writing with incremented version.
                    // If another node already claimed it, saveOrFail throws
                    // StaleStateException and we skip it.
                    LRAState claimed = lraStore.saveOrFail(lraId, state, state.getVersion());
                    doRecoverTransactionFromState(lraId, claimed);
                    recoveryCount++;
                } catch (StaleStateException e) {
                    // Another node claimed it — skip
                    skippedCount++;
                    if (LRALogger.logger.isTraceEnabled()) {
                        LRALogger.logger.tracef(
                                "LRARecoveryModule: Skipping LRA %s (claimed by another node)", lraId);
                    }
                } catch (Exception e) {
                    LRALogger.logger.warnf(e, "Error recovering LRA %s from distributed store", lraId);
                }
            }

            if (LRALogger.logger.isDebugEnabled()) {
                LRALogger.logger.debugf(
                        "LRARecoveryModule: Recovered %d LRAs from distributed store (%d skipped - claimed by other nodes)",
                        recoveryCount, skippedCount);
            }

        } catch (Exception e) {
            LRALogger.logger.errorf(e, "Error during distributed store recovery, falling back to ObjectStore");
            recoverTransactionsFromObjectStore();
        }
    }

    /**
     * Scans active LRAs in the distributed store for expired timeouts and
     * moves them to the recovering cache.
     *
     * This is needed because if the creating coordinator dies before its local
     * {@code scheduledAbort} fires, the timed-out LRA stays in the active cache
     * and {@link #recoverTransactionsFromDistributedStore()} never sees it
     * (it only scans the recovering cache). Moving the entry here makes it
     * visible to the normal recovery path, which loads it via
     * {@code restore_state()} — that method already detects the expired
     * {@code finishTime} and schedules cancellation.
     *
     * Only runs in HA mode; single-instance mode relies on local schedulers.
     */
    private void checkForTimedOutLRAs() {
        if (!haEnabled || lraStore == null) {
            return;
        }

        try {
            Map<String, LRAState> activeLRAs = lraStore.getAllActiveLRAs();

            if (activeLRAs == null || activeLRAs.isEmpty()) {
                return;
            }

            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            int movedCount = 0;

            for (Entry<String, LRAState> entry : activeLRAs.entrySet()) {
                LRAState state = entry.getValue();

                if (state.getFinishTime() != null && now.isAfter(state.getFinishTime())
                        && state.getStatus() == LRAStatus.Active) {
                    URI lraId = state.getId();

                    // Move to the recovering cache so that
                    // recoverTransactionsFromDistributedStore() picks it up.
                    // Uses CAS — if another node already moved it, we skip.
                    if (lraStore.moveToRecovering(lraId, state, state.getVersion())) {
                        movedCount++;
                    }

                    if (LRALogger.logger.isDebugEnabled()) {
                        LRALogger.logger.debugf(
                                "LRARecoveryModule: moved timed-out LRA %s to recovering cache (finishTime=%s)",
                                lraId, state.getFinishTime());
                    }
                }
            }

            if (movedCount > 0 && LRALogger.logger.isInfoEnabled()) {
                LRALogger.logger.infof(
                        "LRARecoveryModule: moved %d timed-out LRAs to recovering cache", movedCount);
            }

        } catch (Exception e) {
            LRALogger.logger.warnf(e, "Error checking for timed-out LRAs");
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
     * Recovers a single LRA from its LRAState (distributed store recovery).
     */
    private void doRecoverTransactionFromState(URI lraId, io.narayana.lra.coordinator.domain.model.LRAState state) {
        try {
            // Extract UID from LRA ID
            String uidString = LRAConstants.getLRAUid(lraId);
            Uid recoverUid = new Uid(uidString);
            // Derive the ActionStatus from the LRAState so that RecoveringLRA.tryReplayPhase2()
            // makes the correct decision about whether/how to replay
            int theStatus = toActionStatus(state.getStatus());
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
                LRALogger.logger.debugf("LRARecoveryModule: recovering LRA %s, status: %s", lraId, lraStatus);
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
     * In HA mode, uses LRAStore.moveToFailed() which is atomic.
     * In single-instance mode, delegates to moveEntryToFailedLRAPath(Uid).
     */
    private boolean moveEntryToFailedLRAPath(URI lraId,
            io.narayana.lra.coordinator.domain.model.LRAState state) {
        if (haEnabled && lraStore != null) {
            // In HA mode, use LRAStore.moveToFailed() which is atomic
            try {
                lraStore.moveToFailed(lraId);
                LRALogger.logger.infof("Failed LRA %s moved to failed state in distributed store", lraId);
                return true;
            } catch (Exception e) {
                LRALogger.logger.warnf(e, "Failed to move LRA %s to failed state in distributed store", lraId);
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

    /**
     * Maps an LRAStatus from the distributed store to the corresponding
     * Arjuna ActionStatus used by RecoveringLRA to drive replay decisions.
     *
     * @param lraStatus the LRA status
     * @return the corresponding ActionStatus
     */
    private static int toActionStatus(LRAStatus lraStatus) {
        switch (lraStatus) {
            case Closing:
                return ActionStatus.COMMITTING;
            case Cancelling:
                return ActionStatus.ABORTING;
            case Closed:
                return ActionStatus.COMMITTED;
            case Cancelled:
                return ActionStatus.ABORTED;
            case FailedToClose:
            case FailedToCancel:
                return ActionStatus.H_HAZARD;
            case Active:
            default:
                return ActionStatus.RUNNING;
        }
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
