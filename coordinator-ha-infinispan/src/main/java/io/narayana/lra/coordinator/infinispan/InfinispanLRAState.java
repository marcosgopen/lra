/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.infinispan;

import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.state.InputObjectState;
import io.narayana.lra.LRAConstants;
import io.narayana.lra.coordinator.domain.model.LRAState;
import io.narayana.lra.coordinator.domain.model.LongRunningAction;
import java.io.Serializable;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.Objects;
import org.eclipse.microprofile.lra.annotation.LRAStatus;
import org.infinispan.protostream.annotations.ProtoFactory;
import org.infinispan.protostream.annotations.ProtoField;

/**
 * Infinispan ProtoStream-serializable implementation of {@link LRAState}.
 *
 * <p>
 * This class carries the {@code @ProtoField} and {@code @ProtoFactory}
 * annotations required for Infinispan cache serialization (embedded mode),
 * and implements {@link Serializable} for WildFly's JBoss Marshalling (subsystem mode).
 * </p>
 *
 * <p>
 * This class is immutable and thread-safe.
 * </p>
 */
public class InfinispanLRAState implements LRAState, Serializable {

    private static final long serialVersionUID = 1L;

    private final URI id;
    private final URI parentId;
    private final String clientId;
    private final LRAStatus status;
    private final LocalDateTime startTime;
    private final LocalDateTime finishTime;
    private final long timeLimit;
    private final String nodeId;
    private final byte[] serializedState;
    private final long version;

    /**
     * ProtoStream deserialization constructor.
     * All parameters use proto-friendly types (String instead of URI/LRAStatus/LocalDateTime).
     */
    @ProtoFactory
    public InfinispanLRAState(String idString, String parentIdString, String clientId, String statusName,
            String startTimeString, String finishTimeString,
            long timeLimit, String nodeId, byte[] serializedState, long version) {
        this.id = idString != null ? URI.create(idString) : null;
        this.parentId = parentIdString != null ? URI.create(parentIdString) : null;
        this.clientId = clientId;
        this.status = statusName != null ? LRAStatus.valueOf(statusName) : null;
        this.startTime = startTimeString != null ? LocalDateTime.parse(startTimeString) : null;
        this.finishTime = finishTimeString != null ? LocalDateTime.parse(finishTimeString) : null;
        this.timeLimit = timeLimit;
        this.nodeId = nodeId;
        this.serializedState = serializedState;
        this.version = version;
    }

    /**
     * Creates an InfinispanLRAState from any {@link LRAState} instance.
     * Used to convert a {@code DefaultLRAState} (from the coordinator module)
     * into a ProtoStream-serializable form for Infinispan cache storage.
     *
     * @param state the LRAState to convert
     * @return the InfinispanLRAState
     */
    public static InfinispanLRAState from(LRAState state) {
        return new InfinispanLRAState(
                state.getId() != null ? state.getId().toString() : null,
                state.getParentId() != null ? state.getParentId().toString() : null,
                state.getClientId(),
                state.getStatus() != null ? state.getStatus().name() : null,
                state.getStartTime() != null ? state.getStartTime().toString() : null,
                state.getFinishTime() != null ? state.getFinishTime().toString() : null,
                state.getTimeLimit(),
                state.getNodeId(),
                state.getSerializedState(),
                state.getVersion());
    }

    /**
     * Creates a copy of this state with a different version number.
     *
     * @param newVersion the new version
     * @return a new InfinispanLRAState with the updated version
     */
    public InfinispanLRAState withVersion(long newVersion) {
        return new InfinispanLRAState(
                id != null ? id.toString() : null,
                parentId != null ? parentId.toString() : null,
                clientId,
                status != null ? status.name() : null,
                startTime != null ? startTime.toString() : null,
                finishTime != null ? finishTime.toString() : null,
                timeLimit,
                nodeId,
                serializedState,
                newVersion);
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

    // ProtoStream field getters (proto-friendly types)

    @ProtoField(number = 1)
    public String getIdString() {
        return id != null ? id.toString() : null;
    }

    @ProtoField(number = 2)
    public String getParentIdString() {
        return parentId != null ? parentId.toString() : null;
    }

    @Override
    @ProtoField(number = 3)
    public String getClientId() {
        return clientId;
    }

    @ProtoField(number = 4)
    public String getStatusName() {
        return status != null ? status.name() : null;
    }

    @ProtoField(number = 5)
    public String getStartTimeString() {
        return startTime != null ? startTime.toString() : null;
    }

    @ProtoField(number = 6)
    public String getFinishTimeString() {
        return finishTime != null ? finishTime.toString() : null;
    }

    @Override
    @ProtoField(number = 7, defaultValue = "0")
    public long getTimeLimit() {
        return timeLimit;
    }

    @Override
    @ProtoField(number = 8)
    public String getNodeId() {
        return nodeId;
    }

    @Override
    @ProtoField(number = 9)
    public byte[] getSerializedState() {
        return serializedState;
    }

    @Override
    @ProtoField(number = 10, defaultValue = "0")
    public long getVersion() {
        return version;
    }

    // Domain getters (return original types)

    @Override
    public URI getId() {
        return id;
    }

    @Override
    public URI getParentId() {
        return parentId;
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
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof InfinispanLRAState))
            return false;
        InfinispanLRAState that = (InfinispanLRAState) o;
        return version == that.version && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, version);
    }

    @Override
    public String toString() {
        return String.format("InfinispanLRAState{id=%s, status=%s, version=%d, nodeId=%s, startTime=%s}",
                id, status, version, nodeId, startTime);
    }
}
