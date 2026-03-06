/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.internal;

import java.net.URI;

/**
 * Thrown when a compare-and-swap (CAS) write to the distributed LRA store
 * fails because the entry was concurrently modified by another node.
 *
 * <p>
 * Callers should reload the current state and retry the operation.
 * </p>
 */
public class StaleStateException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final URI lraId;
    private final long expectedVersion;
    private final long actualVersion;

    public StaleStateException(URI lraId, long expectedVersion, long actualVersion) {
        super(String.format("CAS conflict for LRA %s: expected version %d but found %d",
                lraId, expectedVersion, actualVersion));
        this.lraId = lraId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public URI getLraId() {
        return lraId;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }

    public long getActualVersion() {
        return actualVersion;
    }
}
