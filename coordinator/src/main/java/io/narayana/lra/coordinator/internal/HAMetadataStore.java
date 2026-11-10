/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.internal;

import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.exceptions.ObjectStoreException;

/**
 * Extension interface for HA-capable object stores.
 *
 * <p>
 * Implemented by {@code InfinispanObjectStore} to expose distributed locking
 * without requiring reflection. {@code LRAService} casts
 * {@code StoreManager.getRecoveryStore()} to this interface when HA mode is active.
 * </p>
 *
 * <p>
 * Both methods may throw {@link StoreUnavailableException} (a subclass of
 * {@link ObjectStoreException}) when the underlying distributed store is in a
 * degraded partition or is not yet available. Callers should treat this as a
 * transient failure and propagate an HTTP 503 rather than an HTTP 500.
 * </p>
 */
public interface HAMetadataStore {

    /**
     * Attempts to acquire an exclusive distributed lock for the given LRA UID.
     *
     * @param uid the UID of the LRA to lock
     * @return {@code true} if this node acquired the lock,
     *         {@code false} if another node holds it
     * @throws StoreUnavailableException if the store is in a degraded partition
     * @throws ObjectStoreException for any other store failure
     */
    boolean tryLock(Uid uid) throws ObjectStoreException;

    /**
     * Releases the distributed lock previously acquired by {@link #tryLock(Uid)}.
     * A no-op if this node does not own the lock.
     *
     * @param uid the UID of the LRA whose lock to release
     * @throws StoreUnavailableException if the store is in a degraded partition
     * @throws ObjectStoreException for any other store failure
     */
    void releaseLock(Uid uid) throws ObjectStoreException;
}
