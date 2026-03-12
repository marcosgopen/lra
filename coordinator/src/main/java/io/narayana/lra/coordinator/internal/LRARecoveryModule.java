/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.internal;

import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.common.recoveryPropertyManager;
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
import jakarta.enterprise.inject.spi.CDI;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.function.Consumer;
import org.eclipse.microprofile.lra.annotation.LRAStatus;

public class LRARecoveryModule implements RecoveryModule,
        ClusterCoordinationService.CoordinatorChangeListener {

    // HA components
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
     * Initializes HA components by looking up ClusterCoordinationService
     * via CDI. Only called when HA mode is enabled — the caller
     * ({@link #ensureHAInitialized()}) guards on the system property.
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

            // Try to get cluster coordinator from CDI
            this.clusterCoordinator = tryGetBean(cdi, ClusterCoordinationService.class);

            if (clusterCoordinator == null) {
                // Bean not available yet — this is expected when called during
                // deployment before CDI has finished processing bean archives.
                // Leave haInitAttempted false so the next call retries.
                LRALogger.logger.warn("HA mode is enabled (lra.coordinator.ha.enabled=true) "
                        + "but ClusterCoordinationService bean is not available yet. "
                        + "Will retry on next access.");
                return;
            }

            this.haEnabled = true;
            service.initializeHA(clusterCoordinator);

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
        recoverTransactionsFromObjectStore();
    }

    /**
     * Recovers LRAs from ObjectStore.
     * In HA mode with InfinispanSlots as the BackingSlots implementation,
     * the ObjectStore reads from the replicated Infinispan cache, so all
     * coordinators see the same data. Only the recovery leader runs this.
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
     * Moves an LRA entry to the failed state via ObjectStore.
     */
    private boolean moveEntryToFailedLRAPath(URI lraId) {
        String uidString = io.narayana.lra.LRAConstants.getLRAUid(lraId);
        Uid uid = new Uid(uidString);
        return moveEntryToFailedLRAPath(uid);
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
