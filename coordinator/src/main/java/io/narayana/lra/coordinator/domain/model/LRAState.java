/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.domain.model;

import com.arjuna.ats.arjuna.state.InputObjectState;
import java.net.URI;
import java.time.LocalDateTime;
import org.eclipse.microprofile.lra.annotation.LRAStatus;

/**
 * Represents the serializable state of an LRA.
 *
 * <p>
 * Used for transferring LRA state between coordinators in an HA setup
 * and for storing state in distributed caches. Implementations provide
 * the actual storage/serialization mechanism (e.g., Infinispan ProtoStream).
 * </p>
 *
 * <p>
 * Implementations must be immutable and thread-safe.
 * </p>
 */
public interface LRAState {

    URI getId();

    URI getParentId();

    String getClientId();

    LRAStatus getStatus();

    LocalDateTime getStartTime();

    LocalDateTime getFinishTime();

    long getTimeLimit();

    String getNodeId();

    byte[] getSerializedState();

    /**
     * Converts this LRAState back to an InputObjectState for deserialization
     * by {@link LongRunningAction#restore_state}.
     *
     * @return the InputObjectState, or null if conversion fails
     */
    InputObjectState toInputObjectState();

    /**
     * Checks if this LRA is in a recovering state (Cancelling or Closing).
     *
     * @return true if the LRA is recovering
     */
    default boolean isRecovering() {
        return getStatus() == LRAStatus.Cancelling || getStatus() == LRAStatus.Closing;
    }

    /**
     * Checks if this LRA has finished.
     *
     * @return true if the LRA has finished
     */
    default boolean isFinished() {
        LRAStatus s = getStatus();
        return s == LRAStatus.Closed || s == LRAStatus.Cancelled
                || s == LRAStatus.FailedToClose || s == LRAStatus.FailedToCancel;
    }
}
