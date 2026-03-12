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
import io.narayana.lra.coordinator.domain.model.LongRunningAction;
import io.narayana.lra.coordinator.internal.LRARecoveryModule;
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

        // Not found in local memory
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

    public synchronized ReentrantLock lockTransaction(URI lraId) {
        ReentrantLock lock = locks.computeIfAbsent(lraId, k -> new ReentrantLock());

        lock.lock();

        return lock;
    }

    public synchronized ReentrantLock tryLockTransaction(URI lraId) {
        ReentrantLock lock = locks.computeIfAbsent(lraId, k -> new ReentrantLock());

        return lock.tryLock() ? lock : null;
    }

    public synchronized ReentrantLock tryTimedLockTransaction(URI lraId, long timeout) {
        ReentrantLock lock = locks.computeIfAbsent(lraId, k -> new ReentrantLock());

        try {
            return lock.tryLock(timeout, MILLISECONDS) ? lock : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public List<LRAData> getAll() {
        return getAll(null);
    }

    public List<LRAData> getAll(LRAStatus lraStatus) {
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

        return new ArrayList<>(result.values());
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

    /**
     * Extracts the LRA UID from an LRA ID URI.
     * Handles both full URIs (http://host/lra-coordinator/uid) and
     * UID-only values (uid) as used by recovery URL path parameters.
     */
    private String extractLraUid(URI lraId) {
        if (lraId == null) {
            return null;
        }
        String uid = LRAConstants.getLRAUid(lraId);
        return (uid != null && !uid.isEmpty()) ? uid : null;
    }

    /**
     * Extracts the LRA UID segment from a recovery URL.
     * Recovery URL format: {base}/lra-coordinator/recovery/{lraUid}/{participantUid}
     * The LRA UID is the second-to-last path segment.
     */
    private String extractLraUidFromRecoveryUrl(String recoveryUrl) {
        try {
            URI uri = new URI(recoveryUrl);
            String path = uri.getPath();
            if (path == null) {
                return null;
            }
            String[] segments = path.split("/");
            if (segments.length < 2) {
                return null;
            }
            return segments[segments.length - 2];
        } catch (URISyntaxException e) {
            return null;
        }
    }

    public synchronized LongRunningAction startLRA(String baseUri, URI parentLRA, String clientId, Long timelimit) {
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
        LongRunningAction lra;

        try {
            lra = getTransaction(lraId);
        } catch (NotFoundException e) {
            return NOT_FOUND.getStatusCode();
        }

        return lra.setTimeLimit(timelimit, true);
    }

    public List<LRAData> getFailedLRAs() {
        Map<URI, LRAData> result = new LinkedHashMap<>();

        Map<URI, LongRunningAction> localFailed = new ConcurrentHashMap<>();
        getRM().getFailedLRAs(localFailed);
        localFailed.values().stream()
                .map(LongRunningAction::getLRAData)
                .forEach(d -> result.put(d.getLraId(), d));

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
}
