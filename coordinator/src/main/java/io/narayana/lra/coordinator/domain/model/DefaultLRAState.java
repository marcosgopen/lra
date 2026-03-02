/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.domain.model;

import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.state.InputObjectState;
import com.arjuna.ats.arjuna.state.OutputObjectState;
import io.narayana.lra.LRAConstants;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDateTime;
import org.eclipse.microprofile.lra.annotation.LRAStatus;

/**
 * Default implementation of {@link LRAState} as a plain POJO.
 *
 * <p>
 * This class has no external serialization dependencies. HA modules
 * (e.g., the Infinispan module) may convert instances of this class into
 * their own serializable representation for distributed storage.
 * </p>
 *
 * <p>
 * This class is immutable and thread-safe.
 * </p>
 */
public class DefaultLRAState implements LRAState {

    private final URI id;
    private final URI parentId;
    private final String clientId;
    private final LRAStatus status;
    private final LocalDateTime startTime;
    private final LocalDateTime finishTime;
    private final long timeLimit;
    private final String nodeId;
    private final byte[] serializedState;

    public DefaultLRAState(URI id, URI parentId, String clientId, LRAStatus status,
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
    public static DefaultLRAState fromLongRunningAction(LongRunningAction lra, OutputObjectState oos)
            throws IOException {
        return new DefaultLRAState(
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

    @Override
    public InputObjectState toInputObjectState() {
        if (serializedState == null || id == null) {
            return null;
        }

        try {
            String uidString = LRAConstants.getLRAUid(id);
            Uid uid = new Uid(uidString);
            String type = LongRunningAction.getType();
            return new InputObjectState(uid, type, serializedState);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public URI getId() {
        return id;
    }

    @Override
    public URI getParentId() {
        return parentId;
    }

    @Override
    public String getClientId() {
        return clientId;
    }

    @Override
    public LRAStatus getStatus() {
        return status;
    }

    @Override
    public LocalDateTime getStartTime() {
        return startTime;
    }

    @Override
    public LocalDateTime getFinishTime() {
        return finishTime;
    }

    @Override
    public long getTimeLimit() {
        return timeLimit;
    }

    @Override
    public String getNodeId() {
        return nodeId;
    }

    @Override
    public byte[] getSerializedState() {
        return serializedState;
    }

    @Override
    public String toString() {
        return String.format("DefaultLRAState{id=%s, status=%s, nodeId=%s, startTime=%s}",
                id, status, nodeId, startTime);
    }
}
