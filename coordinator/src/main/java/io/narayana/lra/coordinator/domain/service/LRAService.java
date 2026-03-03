/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.domain.service;

import static jakarta.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;
import static jakarta.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toList;

import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.coordinator.ActionStatus;
import com.arjuna.ats.arjuna.coordinator.BasicAction;
import com.arjuna.ats.arjuna.recovery.RecoveryManager;
import io.narayana.lra.LRAConstants;
import io.narayana.lra.LRAData;
import io.narayana.lra.coordinator.domain.model.LRAParticipantRecord;
import io.narayana.lra.coordinator.domain.model.LRAState;
import io.narayana.lra.coordinator.domain.model.LongRunningAction;
import io.narayana.lra.coordinator.internal.ClusterCoordinationService;
import io.narayana.lra.coordinator.internal.LRARecoveryModule;
import io.narayana.lra.coordinator.internal.LRAStore;
import io.narayana.lra.coordinator.internal.LockManager;
import io.narayana.lra.logging.LRALogger;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.lra.annotation.LRAStatus;

public class LRAService {
    private static final Pattern LINK_REL_PATTERN = Pattern.compile("(\\w+)=\"([^\"]+)\"|([^\\s]+)");

    private final Map<URI, LongRunningAction> lras = new ConcurrentHashMap<>();
    private final Map<URI, LongRunningAction> recoveringLRAs = new ConcurrentHashMap<>();
    private final Map<URI, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<LongRunningAction, Map<String, String>> lraParticipants = new ConcurrentHashMap<>();
    private LRARecoveryModule recoveryModule;

    // HA components (injected by LRARecoveryModule when HA is enabled)
    private LRAStore lraStore;
    private LockManager lockManager;
    private ClusterCoordinationService clusterCoordinator;
    private String nodeId;
    private boolean haEnabled = false;

    // Map to track distributed lock handles for cleanup
    private final Map<URI, LockManager.LockHandle> lockHandles = new ConcurrentHashMap<>();

    /**
     * Gets a transaction by LRA ID.
     *
     * This method is now race-condition free by using atomic operations.
     * In HA mode, it will also attempt to load the LRA from the distributed store if not in memory.
     *
     * @param lraId the LRA ID
     * @return the LongRunningAction
     * @throws NotFoundException if the LRA cannot be found
     */
    public LongRunningAction getTransaction(URI lraId) throws NotFoundException {
        // Fast path: check active LRAs first (atomic get)
        LongRunningAction lra = lras.get(lraId);
        if (lra != null) {
            return lra;
        }

        // Check recovering LRAs (atomic get)
        lra = recoveringLRAs.get(lraId);
        if (lra != null) {
            return lra;
        }

        // Extract UID for alternative lookups
        String uid = LRAConstants.getLRAUid(lraId);
        if (uid == null || uid.isEmpty()) {
            String errorMsg = LRALogger.i18nLogger.warn_invalid_uri(
                    String.valueOf(lraId), "LongRunningAction.getTransaction");
            throw new NotFoundException(errorMsg,
                    Response.status(NOT_FOUND).entity(errorMsg).build());
        }

        // Try comparing on UID since different URIs can map to the same resource
        // (e.g., localhost vs 127.0.0.1 vs ::1)
        lra = findByUid(lras, uid);
        if (lra != null) {
            return lra;
        }

        lra = findByUid(recoveringLRAs, uid);
        if (lra != null) {
            return lra;
        }

        // In HA mode, try to load from distributed store atomically
        if (haEnabled && lraStore != null) {
            // Check if cache is available (not in minority partition)
            if (!lraStore.isAvailable()) {
                String errorMsg = "Coordinator in minority partition - cannot access LRA state";
                throw new WebApplicationException(errorMsg,
                        Response.status(SERVICE_UNAVAILABLE).entity(errorMsg).build());
            }

            // Use computeIfAbsent to atomically check and load
            lra = lras.computeIfAbsent(lraId, key -> {
                try {
                    LRAState state = lraStore.loadLRA(key);
                    if (state != null) {
                        // Create a RecoveringLRA from the state
                        LongRunningAction recoveredLra = new LongRunningAction(this,
                                new com.arjuna.ats.arjuna.common.Uid(uid));

                        if (recoveredLra.fromLRAState(state)) {
                            LRALogger.logger.infof("Loaded LRA %s from distributed store", key);
                            return recoveredLra;
                        } else {
                            LRALogger.logger.warnf("Failed to restore LRA %s from state", key);
                        }
                    }
                } catch (Exception e) {
                    LRALogger.logger.warnf(e, "Error loading LRA %s from distributed store", key);
                }
                return null;
            });

            if (lra != null) {
                return lra;
            }
        }

        // Not found anywhere
        String errorMsg = "Cannot find transaction id: " + lraId;
        throw new NotFoundException(errorMsg,
                Response.status(NOT_FOUND).entity(errorMsg).build());
    }

    /**
     * Helper method to find an LRA by UID in a map.
     * This handles cases where the URI format differs but the UID is the same.
     */
    private LongRunningAction findByUid(Map<URI, LongRunningAction> map, String uid) {
        for (LongRunningAction lra : map.values()) {
            if (uid.equals(lra.get_uid().fileStringForm())) {
                return lra;
            }
        }
        return null;
    }

    public LongRunningAction lookupTransaction(URI lraId) {
        try {
            return lraId == null ? null : getTransaction(lraId);
        } catch (NotFoundException e) {
            return null;
        }
    }

    public LRAData getLRA(URI lraId) {
        LongRunningAction lra = getTransaction(lraId);
        return lra.getLRAData();
    }

    /**
     * Acquires a lock for an LRA (blocking).
     * In HA mode, acquires both distributed and local locks.
     * <p>
     * The distributed lock is only acquired on the first (non-reentrant) call.
     * Infinispan's {@code ClusteredLock} is not reentrant, so a second
     * acquisition from the same node would deadlock. Re-entrant calls are
     * detected via the local {@code ReentrantLock} and skip the distributed
     * lock entirely.
     *
     * @param lraId the LRA ID
     * @return the lock (caller must unlock in finally block)
     */
    public synchronized ReentrantLock lockTransaction(URI lraId) {
        ReentrantLock lock = locks.computeIfAbsent(lraId, k -> new HAReentrantLock(lraId, this));

        // If the current thread already holds the local lock this is a
        // reentrant call — just re-enter the local lock and skip the
        // distributed lock (ClusteredLock is not reentrant).
        if (lock.isHeldByCurrentThread()) {
            lock.lock();
            return lock;
        }

        // In HA mode, acquire distributed lock first
        if (haEnabled && lockManager != null) {
            LockManager.LockHandle distributedLock = lockManager.acquireLock(lraId);
            if (distributedLock == null) {
                LRALogger.logger.warnf("Failed to acquire distributed lock for LRA %s", lraId);
                return null;
            }
            lockHandles.put(lraId, distributedLock);
        }

        try {
            lock.lock();
        } catch (Error | RuntimeException e) {
            // Local lock failed — release the distributed lock so it is
            // not held indefinitely.
            releaseDistributedLock(lraId);
            throw e;
        }

        return lock;
    }

    /**
     * Tries to acquire a lock for an LRA (non-blocking).
     * In HA mode, acquires both distributed and local locks.
     *
     * @param lraId the LRA ID
     * @return the lock if acquired, null otherwise
     */
    public synchronized ReentrantLock tryLockTransaction(URI lraId) {
        ReentrantLock lock = locks.computeIfAbsent(lraId, k -> new HAReentrantLock(lraId, this));

        if (lock.isHeldByCurrentThread()) {
            lock.lock();
            return lock;
        }

        // In HA mode, try to acquire distributed lock first
        if (haEnabled && lockManager != null) {
            LockManager.LockHandle distributedLock = lockManager.acquireLock(lraId, 0, MILLISECONDS);
            if (distributedLock == null) {
                return null; // Failed to acquire distributed lock
            }
            lockHandles.put(lraId, distributedLock);
        }

        // Try to acquire local lock
        if (lock.tryLock()) {
            return lock;
        } else {
            // Failed to acquire local lock, release distributed lock
            releaseDistributedLock(lraId);
            return null;
        }
    }

    /**
     * Tries to acquire a lock for an LRA with timeout.
     * In HA mode, acquires both distributed and local locks.
     *
     * @param lraId the LRA ID
     * @param timeout the timeout in milliseconds
     * @return the lock if acquired, null otherwise
     */
    public synchronized ReentrantLock tryTimedLockTransaction(URI lraId, long timeout) {
        ReentrantLock lock = locks.computeIfAbsent(lraId, k -> new HAReentrantLock(lraId, this));

        if (lock.isHeldByCurrentThread()) {
            lock.lock();
            return lock;
        }

        // In HA mode, try to acquire distributed lock first with timeout
        if (haEnabled && lockManager != null) {
            LockManager.LockHandle distributedLock = lockManager.acquireLock(lraId, timeout,
                    MILLISECONDS);
            if (distributedLock == null) {
                return null; // Failed to acquire distributed lock
            }
            lockHandles.put(lraId, distributedLock);
        }

        // Try to acquire local lock with timeout
        try {
            if (lock.tryLock(timeout, MILLISECONDS)) {
                return lock;
            } else {
                // Failed to acquire local lock, release distributed lock
                releaseDistributedLock(lraId);
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            releaseDistributedLock(lraId);
            return null;
        }
    }

    /**
     * Releases the distributed lock for an LRA (if in HA mode).
     * Called internally when lock acquisition fails or when unlock is called.
     *
     * @param lraId the LRA ID
     */
    void releaseDistributedLock(URI lraId) {
        if (haEnabled) {
            LockManager.LockHandle handle = lockHandles.remove(lraId);
            if (handle != null) {
                try {
                    handle.release();
                } catch (Exception e) {
                    LRALogger.logger.warnf(e, "Error releasing distributed lock for LRA %s", lraId);
                }
            }
        }
    }

    /**
     * Custom ReentrantLock that also releases distributed locks in HA mode.
     * This maintains backward compatibility while adding distributed locking.
     */
    private static class HAReentrantLock extends ReentrantLock {
        private final URI lraId;
        private final LRAService lraService;

        HAReentrantLock(URI lraId, LRAService lraService) {
            this.lraId = lraId;
            this.lraService = lraService;
        }

        @Override
        public void unlock() {
            try {
                super.unlock();
            } finally {
                // Also release distributed lock if in HA mode
                // Only release if this is the final unlock (not held by this thread anymore)
                if (!isHeldByCurrentThread()) {
                    lraService.releaseDistributedLock(lraId);
                }
            }
        }
    }

    public List<LRAData> getAll() {
        return getAll(null);
    }

    public List<LRAData> getAll(LRAStatus lraStatus) {
        // Collect local LRAs
        Map<URI, LRAData> result = new LinkedHashMap<>();

        if (lraStatus == null) {
            lras.values().stream()
                    .map(LongRunningAction::getLRAData)
                    .forEach(d -> result.put(d.getLraId(), d));
            recoveringLRAs.values().stream()
                    .map(LongRunningAction::getLRAData)
                    .forEach(d -> result.put(d.getLraId(), d));
        } else {
            getDataByStatus(lras, lraStatus)
                    .forEach(d -> result.put(d.getLraId(), d));
            getDataByStatus(recoveringLRAs, lraStatus)
                    .forEach(d -> result.put(d.getLraId(), d));
        }

        // In HA mode, also include LRAs from the distributed store
        // that are not yet loaded into local memory
        if (haEnabled && lraStore != null && lraStore.isAvailable()) {
            mergeDistributedLRAs(result, lraStatus);
        }

        return new ArrayList<>(result.values());
    }

    /**
     * Merges LRAs from the distributed Infinispan cache into the result map.
     * Only adds entries that are not already present (local state takes precedence).
     */
    private void mergeDistributedLRAs(Map<URI, LRAData> result, LRAStatus statusFilter) {
        try {
            // Active LRAs
            if (statusFilter == null || statusFilter == LRAStatus.Active) {
                for (Map.Entry<String, LRAState> entry : lraStore.getAllActiveLRAs().entrySet()) {
                    LRAState state = entry.getValue();
                    result.putIfAbsent(state.getId(), toLRAData(state));
                }
            }

            // Recovering LRAs (Closing, Cancelling, Closed, Cancelled)
            if (statusFilter == null || statusFilter == LRAStatus.Closing
                    || statusFilter == LRAStatus.Cancelling
                    || statusFilter == LRAStatus.Closed
                    || statusFilter == LRAStatus.Cancelled) {
                for (LRAState state : lraStore.getAllRecoveringLRAs()) {
                    if (statusFilter == null || state.getStatus() == statusFilter) {
                        result.putIfAbsent(state.getId(), toLRAData(state));
                    }
                }
            }

            // Failed LRAs
            if (statusFilter == null || statusFilter == LRAStatus.FailedToClose
                    || statusFilter == LRAStatus.FailedToCancel) {
                for (LRAState state : lraStore.getAllFailedLRAs()) {
                    if (statusFilter == null || state.getStatus() == statusFilter) {
                        result.putIfAbsent(state.getId(), toLRAData(state));
                    }
                }
            }
        } catch (Exception e) {
            LRALogger.logger.warnf(e, "Error merging LRAs from distributed store");
        }
    }

    /**
     * Converts an LRAState from the distributed store into an LRAData DTO.
     */
    private LRAData toLRAData(LRAState state) {
        return new LRAData(
                state.getId(),
                state.getClientId(),
                state.getStatus(),
                state.getParentId() == null, // isTopLevel
                state.isRecovering(),
                state.getStartTime() != null
                        ? state.getStartTime().atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                        : 0L,
                state.getFinishTime() != null
                        ? state.getFinishTime().atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                        : 0L,
                0 // httpStatus is not tracked in LRAState
        );
    }

    /**
     * Getting all the LRA managed by recovery manager. This means all LRAs which are not mapped
     * only in memory but that were already saved in object store.
     *
     * @param scan defines if there is run recovery manager scanning before returning the collection,
     *        when the recovery is run then the object store is touched and the returned
     *        list may be updated with the new loaded objects
     * @return list of the {@link LRAData} which define the recovering LRAs
     */
    public List<LRAData> getAllRecovering(boolean scan) {
        if (scan) {
            RecoveryManager.manager().scan();
        }

        return recoveringLRAs.values().stream().map(LongRunningAction::getLRAData).collect(toList());
    }

    public List<LRAData> getAllRecovering() {
        return getAllRecovering(false);
    }

    public void addTransaction(LongRunningAction lra) {
        // Inject LRAStore for HA mode
        if (haEnabled && lraStore != null) {
            lra.setLRAStore(lraStore);
        }

        lras.putIfAbsent(lra.getId(), lra);
    }

    public void finished(LongRunningAction transaction, boolean fromHierarchy) {
        if (transaction.isFailed()) {
            getRM().moveEntryToFailedLRAPath(transaction.get_uid());
        }
        if (transaction.isRecovering()) {
            recoveringLRAs.put(transaction.getId(), transaction);
        } else if (fromHierarchy || transaction.isTopLevel()) {
            // the LRA is top level or it's a nested LRA that was closed by a
            // parent LRA (ie when fromHierarchy is true) then it's okay to forget about the LRA

            if (!transaction.hasPendingActions()) {
                // this call is only required to clean up cached LRAs (JBTM-3250 will remove this cache).
                remove(transaction);
            }
        }
    }

    /**
     * Remove a log corresponding to an LRA record
     *
     * @param lraId the id of the LRA
     * @return true if the record was either removed or was not present
     */
    public boolean removeLog(String lraId) {
        // LRA ids are URIs with the arjuna uid forming the last segment
        String uid = LRAConstants.getLRAUid(lraId);

        try {
            return getRM().removeCommitted(new Uid(uid));
        } catch (Exception e) {
            LRALogger.i18nLogger.warn_cannotRemoveUidRecord(lraId, uid, e);
            return false;
        }
    }

    public void remove(LongRunningAction lra) {
        if (lra.isFailed()) { // persist failed LRA state
            lra.deactivate();
        }
        remove(lra.getId());
    }

    public void remove(URI lraId) {
        lraTrace(lraId, "remove LRA");

        LongRunningAction lra = lras.remove(lraId);

        if (lra != null) {
            lraParticipants.remove(lra);
        }

        recoveringLRAs.remove(lraId);

        locks.remove(lraId);
    }

    public void recover() {
        getRM().recover();
    }

    // perform a recovery scan to load any recovering LRAs from the store
    public void scan() {
        getRM().periodicWorkSecondPass(); // periodicWorkFirstPass is a no-op
    }

    public boolean updateRecoveryURI(URI lraId, String compensatorUrl, String recoveryURI, boolean persist) {
        assert recoveryURI != null;
        assert compensatorUrl != null;
        LongRunningAction transaction = getTransaction(lraId);
        Map<String, String> participants = lraParticipants.get(transaction);

        // the <participants> collection should be thread safe against update requests, even though such concurrent
        // updates are improbable because only LRAService.joinLRA and RecoveryCoordinator.replaceCompensator
        // do updates but those are sequential operations anyway
        if (participants == null) {
            participants = new ConcurrentHashMap<>();
            participants.put(recoveryURI, compensatorUrl);
            lraParticipants.put(transaction, participants);
        } else {
            participants.replace(recoveryURI, compensatorUrl);
        }

        if (persist) {
            return transaction.updateRecoveryURI(compensatorUrl, recoveryURI);
        }

        return true;
    }

    public String getParticipant(String rcvCoordId) {
        for (Map<String, String> compensators : lraParticipants.values()) {
            String compensator = compensators.get(rcvCoordId);

            if (compensator != null) {
                return compensator;
            }
        }

        return null;
    }

    public synchronized LongRunningAction startLRA(String baseUri, URI parentLRA, String clientId, Long timelimit) {
        // In HA mode, check if cache is available before starting new LRA
        if (haEnabled && lraStore != null && !lraStore.isAvailable()) {
            String errorMsg = "Coordinator in minority partition - cannot start new LRA";
            throw new WebApplicationException(errorMsg,
                    Response.status(SERVICE_UNAVAILABLE).entity(errorMsg).build());
        }

        LongRunningAction lra;
        int status;

        try {
            lra = new LongRunningAction(this, baseUri, lookupTransaction(parentLRA), clientId);
        } catch (URISyntaxException e) {
            throw new WebApplicationException(e.getMessage(),
                    Response.status(Response.Status.PRECONDITION_FAILED)
                            .entity(String.format("Invalid base URI: '%s'", baseUri))
                            .build());
        }

        status = lra.begin(timelimit);

        if (lra.getLRAStatus() == null) {
            // unable to save state, tell the caller to try again later
            throw new WebApplicationException(Response.status(SERVICE_UNAVAILABLE)
                    .entity(LRALogger.i18nLogger.warn_saveState(LongRunningAction.DEACTIVATE_REASON))
                    .build());
        }

        if (status != ActionStatus.RUNNING) {
            lraTrace(lra.getId(), "failed to start LRA");
            lra.finishLRA(true);

            String errorMsg = "Could not start LRA: " + ActionStatus.stringForm(status);

            throw new WebApplicationException(Response.status(INTERNAL_SERVER_ERROR)
                    .entity(errorMsg)
                    .build());
        } else {
            addTransaction(lra);

            return lra;
        }
    }

    public LRAData endLRA(URI lraId, boolean compensate, boolean fromHierarchy) {
        return endLRA(lraId, compensate, fromHierarchy, null, null);
    }

    public LRAData endLRA(URI lraId, boolean compensate, boolean fromHierarchy, String compensator, String userData) {
        lraTrace(lraId, "end LRA");

        // In HA mode, reject close/cancel if we are in a minority partition.
        // Without this check, an LRA loaded from the in-memory map could be
        // ended while the distributed lock service and store are unavailable,
        // risking split-brain decisions.
        if (haEnabled && lraStore != null && !lraStore.isAvailable()) {
            String errorMsg = "Coordinator in minority partition - cannot end LRA";
            throw new WebApplicationException(errorMsg,
                    Response.status(SERVICE_UNAVAILABLE).entity(errorMsg).build());
        }

        LongRunningAction transaction = getTransaction(lraId);

        if (transaction.getLRAStatus() != LRAStatus.Active && !transaction.isRecovering() && transaction.isTopLevel()) {
            String errorMsg = String.format("%s: LRA is closing or closed: endLRA", lraId);
            throw new WebApplicationException(errorMsg, Response.status(Response.Status.PRECONDITION_FAILED)
                    .entity(errorMsg).build());
        }

        transaction.finishLRA(compensate, compensator, userData);

        if (BasicAction.Current() != null) {
            if (LRALogger.logger.isInfoEnabled()) {
                LRALogger.logger.infof("LRAServicve.endLRA LRA %s ended but is still associated with %s%n",
                        lraId, BasicAction.Current().get_uid().fileStringForm());
            }
        }

        finished(transaction, fromHierarchy);

        return transaction.getLRAData();
    }

    public int leave(URI lraId, String compensatorUrl) {
        lraTrace(lraId, "leave LRA");

        LongRunningAction transaction = getTransaction(lraId);

        if (transaction.getLRAStatus() != LRAStatus.Active) {
            return Response.Status.PRECONDITION_FAILED.getStatusCode();
        }

        boolean wasForgotten;
        try {
            wasForgotten = transaction.forgetParticipant(compensatorUrl);
        } catch (Exception e) {
            String errorMsg = String.format("LRAService.forget %s failed on finding participant '%s'", lraId, compensatorUrl);
            throw new WebApplicationException(errorMsg, e, Response.status(Response.Status.BAD_REQUEST)
                    .entity(errorMsg).build());
        }
        if (wasForgotten) {
            return Response.Status.OK.getStatusCode();
        } else {
            String errorMsg = String.format(
                    "LRAService.forget %s failed as the participant was not found, compensator url '%s'",
                    lraId, compensatorUrl);
            throw new WebApplicationException(errorMsg, Response.status(Response.Status.BAD_REQUEST)
                    .entity(errorMsg).build());
        }
    }

    public int joinLRA(StringBuilder recoveryUrl, URI lra, long timeLimit,
            String compensatorUrl, String linkHeader, String recoveryUrlBase,
            StringBuilder compensatorData) {
        return joinLRA(recoveryUrl, lra, timeLimit, compensatorUrl, linkHeader, recoveryUrlBase, compensatorData, null);
    }

    public int joinLRA(StringBuilder recoveryUrl, URI lra, long timeLimit,
            String compensatorUrl, String linkHeader, String recoveryUrlBase,
            StringBuilder compensatorData, String version) {
        if (lra == null) {
            lraTrace(null, "Error missing LRA header in join request");
        } else {
            lraTrace(lra, "join LRA");
        }

        LongRunningAction transaction = getTransaction(lra);

        if (timeLimit < 0) {
            timeLimit = 0;
        }

        // the tx must be either Active (for participants with the @Compensate methods) or
        // Closing/Canceling (for the AfterLRA listeners)
        if (transaction.getLRAStatus() != LRAStatus.Active && !transaction.isRecovering()) {
            // validate that the party wanting to join with this LRA is a listener only:
            if (linkHeader != null) {
                Matcher relMatcher = LINK_REL_PATTERN.matcher(linkHeader);

                while (relMatcher.find()) {
                    String key = relMatcher.group(1);

                    if (key != null && key.equals("rel")) {
                        String rel = relMatcher.group(2) == null ? relMatcher.group(3) : relMatcher.group(2);

                        if (!LRAConstants.AFTER.equals(rel)) {
                            // participants are not allowed to join inactive LRAs
                            return Response.Status.PRECONDITION_FAILED.getStatusCode();
                        } else if (!transaction.isRecovering()) {
                            // listeners cannot be notified if the LRA has already ended
                            return Response.Status.PRECONDITION_FAILED.getStatusCode();
                        }
                    }
                }
            }
        }

        LRAParticipantRecord participant;

        try {
            if (compensatorData != null) {
                participant = transaction.enlistParticipant(lra,
                        linkHeader != null ? linkHeader : compensatorUrl, recoveryUrlBase,
                        timeLimit, compensatorData.toString(), version);
                // return any previously registered data
                compensatorData.setLength(0);

                if (participant != null && participant.getPreviousCompensatorData() != null) {
                    compensatorData.append(participant.getPreviousCompensatorData());
                }
            } else {
                participant = transaction.enlistParticipant(lra,
                        linkHeader != null ? linkHeader : compensatorUrl, recoveryUrlBase,
                        timeLimit, null, version);
            }
        } catch (UnsupportedEncodingException e) {
            return Response.Status.PRECONDITION_FAILED.getStatusCode();
        }

        if (participant == null || participant.getRecoveryURI() == null) {
            // probably already closing or cancelling
            return Response.Status.PRECONDITION_FAILED.getStatusCode();
        }

        String recoveryURI = participant.getRecoveryURI().toASCIIString();

        if (!updateRecoveryURI(lra, participant.getParticipantURI(), recoveryURI, false)) {
            String msg = LRALogger.i18nLogger.warn_saveState(LongRunningAction.DEACTIVATE_REASON);
            throw new WebApplicationException(msg, Response.status(SERVICE_UNAVAILABLE)
                    .entity(msg)
                    .build());
        }

        recoveryUrl.append(recoveryURI);

        return Response.Status.OK.getStatusCode();
    }

    public boolean hasTransaction(URI id) {
        return id != null && (lras.containsKey(id) || recoveringLRAs.containsKey(id));
    }

    public boolean hasTransaction(String id) {
        try {
            return lras.containsKey(new URI(id));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private void lraTrace(URI lraId, String reason) {
        if (LRALogger.logger.isTraceEnabled()) {
            if (lraId != null && lras.containsKey(lraId)) {
                LongRunningAction lra = lras.get(lraId);
                LRALogger.logger.tracef("LRAService: '%s' (%s) in state %s: %s%n",
                        reason, lra.getClientId(), ActionStatus.stringForm(lra.status()), lra.getId());
            } else {
                LRALogger.logger.tracef("LRAService: '%s', not found: %s%n", reason, lraId);
            }
        }
    }

    public int renewTimeLimit(URI lraId, Long timelimit) {
        LongRunningAction lra = lras.get(lraId);

        if (lra == null) {
            return NOT_FOUND.getStatusCode();
        }

        return lra.setTimeLimit(timelimit, true);
    }

    public List<LRAData> getFailedLRAs() {
        Map<URI, LRAData> result = new LinkedHashMap<>();

        // Local failed LRAs from ObjectStore
        Map<URI, LongRunningAction> localFailed = new ConcurrentHashMap<>();
        getRM().getFailedLRAs(localFailed);
        localFailed.values().stream()
                .map(LongRunningAction::getLRAData)
                .forEach(d -> result.put(d.getLraId(), d));

        // In HA mode, also include failed LRAs from the distributed store
        if (haEnabled && lraStore != null && lraStore.isAvailable()) {
            try {
                for (LRAState state : lraStore.getAllFailedLRAs()) {
                    result.putIfAbsent(state.getId(), toLRAData(state));
                }
            } catch (Exception e) {
                LRALogger.logger.warnf(e, "Error getting failed LRAs from distributed store");
            }
        }

        return new ArrayList<>(result.values());
    }

    private LRARecoveryModule getRM() {
        // since this method is reentrant we do not need any synchronization
        if (recoveryModule == null) {
            recoveryModule = LRARecoveryModule.getInstance();
        }

        return recoveryModule;
    }

    private List<LRAData> getDataByStatus(Map<URI, LongRunningAction> lrasToFilter, LRAStatus status) {
        return lrasToFilter.values().stream().filter(t -> t.getLRAStatus() == status)
                .map(LongRunningAction::getLRAData).collect(toList());
    }

    // HA-related methods

    /**
     * Initializes HA components and node ID.
     * Called by LRARecoveryModule when HA is enabled.
     *
     * @param lraStore the LRA store implementation
     * @param lockManager the lock manager
     * @param clusterCoordinator the cluster coordinator
     */
    public void initializeHA(LRAStore lraStore,
            LockManager lockManager,
            ClusterCoordinationService clusterCoordinator) {
        this.lraStore = lraStore;
        this.lockManager = lockManager;
        this.clusterCoordinator = clusterCoordinator;
        // The caller (LRARecoveryModule) only calls this method when the
        // configuration property lra.coordinator.ha.enabled=true, so we
        // trust that decision here rather than re-reading the property.
        this.haEnabled = true;

        // Initialize node ID
        initializeNodeId();

        LRALogger.logger.infof("LRAService initialized with HA mode: %s, node ID: %s",
                haEnabled, nodeId);
    }

    /**
     * Initializes the node ID for this coordinator instance. Tries in order:
     * 1. System property: lra.coordinator.node.id
     * 2. Narayana node identifier
     */
    private void initializeNodeId() {
        // Try system property first
        nodeId = System.getProperty("lra.coordinator.node.id");

        if (nodeId == null || nodeId.isEmpty()) {
            // Fallback to Narayana node identifier
            try {
                String narayanaNodeId = com.arjuna.ats.arjuna.common.arjPropertyManager
                        .getCoreEnvironmentBean().getNodeIdentifier();
                nodeId = "node-" + narayanaNodeId;
            } catch (Exception e) {
                // Final fallback
                nodeId = "node-" + System.currentTimeMillis();
                LRALogger.logger.warnf("Failed to get Narayana node identifier, using timestamp: %s", nodeId);
            }
        }

        LRALogger.logger.infof("Initialized coordinator node ID: %s", nodeId);
    }

    /**
     * Gets the node ID for this coordinator instance.
     *
     * @return the node ID
     */
    public String getNodeId() {
        if (nodeId == null) {
            initializeNodeId();
        }
        return nodeId;
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
     * Gets the LRA store (may be null if HA is disabled).
     *
     * @return the LRA store or null
     */
    public LRAStore getLRAStore() {
        return lraStore;
    }

    /**
     * Gets the lock manager (may be null if HA is disabled).
     *
     * @return the lock manager or null
     */
    public LockManager getLockManager() {
        return lockManager;
    }

    /**
     * Gets the cluster coordinator (may be null if HA is disabled).
     *
     * @return the cluster coordinator or null
     */
    public ClusterCoordinationService getClusterCoordinator() {
        return clusterCoordinator;
    }
}
