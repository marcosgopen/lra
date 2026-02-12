/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.domain.model;

import com.arjuna.ats.arjuna.state.InputObjectState;
import com.arjuna.ats.arjuna.state.OutputObjectState;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.time.LocalDateTime;
import org.eclipse.microprofile.lra.annotation.LRAStatus;

/**
 * Serializable representation of an LRA state.
 * Used for storing LRA state in distributed caches (Infinispan) or for
 * transferring state between coordinators in an HA setup.
 *
 * This class is immutable and thread-safe.
 */
public class LRAState implements Serializable {
    private static final long serialVersionUID = 1L;

    private final URI id;
    private final URI parentId;
    private final String clientId;
    private final LRAStatus status;
    private final LocalDateTime startTime;
    private final LocalDateTime finishTime;
    private final long timeLimit;
    private final String nodeId;
    private final byte[] serializedState; // OutputObjectState bytes

    private LRAState(URI id, URI parentId, String clientId, LRAStatus status,
            LocalDateTime startTime, LocalDateTime finishTime,
            long timeLimit, String nodeId, byte[] serializedState) {
        this.id = id;
        this.parentId = parentId;
        this.clientId = clientId;
        this.status = status;
        this.startTime = startTime;
        this.finishTime = finishTime;
        this.timeLimit = timeLimit;
        this.nodeId = nodeId;
        this.serializedState = serializedState;
    }

    /**
     * Creates an LRAState from a LongRunningAction.
     *
     * @param lra the LRA to serialize
     * @param oos the OutputObjectState containing the full serialized state
     * @return the LRAState
     * @throws IOException if serialization fails
     */
    public static LRAState fromLongRunningAction(LongRunningAction lra, OutputObjectState oos)
            throws IOException {
        return new LRAState(
                lra.getId(),
                lra.getParentId(),
                lra.getClientId(),
                lra.getLRAStatus(),
                lra.getStartTime(),
                lra.getFinishTime(),
                lra.getTimeLimit(),
                lra.getLraService() != null ? lra.getLraService().getNodeId() : null,
                oos.buffer());
    }

    /**
     * Converts this LRAState back to an InputObjectState for deserialization.
     *
     * @return the InputObjectState
     */
    public InputObjectState toInputObjectState() {
        if (serializedState == null) {
            return null;
        }
        return new InputObjectState(serializedState);
    }

    // Getters

    public URI getId() {
        return id;
    }

    public URI getParentId() {
        return parentId;
    }

    public String getClientId() {
        return clientId;
    }

    public LRAStatus getStatus() {
        return status;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public LocalDateTime getFinishTime() {
        return finishTime;
    }

    public long getTimeLimit() {
        return timeLimit;
    }

    public String getNodeId() {
        return nodeId;
    }

    public byte[] getSerializedState() {
        return serializedState;
    }

    /**
     * Checks if this LRA is in a recovering state.
     *
     * @return true if the LRA is recovering
     */
    public boolean isRecovering() {
        return status == LRAStatus.Cancelling || status == LRAStatus.Closing;
    }

    /**
     * Checks if this LRA has finished.
     *
     * @return true if the LRA has finished
     */
    public boolean isFinished() {
        switch (status) {
            case Closed:
            case Cancelled:
            case FailedToClose:
            case FailedToCancel:
                return true;
            default:
                return false;
        }
    }

    @Override
    public String toString() {
        return String.format("LRAState{id=%s, status=%s, nodeId=%s, startTime=%s}",
                id, status, nodeId, startTime);
    }
}
