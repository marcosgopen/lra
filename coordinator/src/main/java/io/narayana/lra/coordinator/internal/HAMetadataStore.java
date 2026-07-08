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
 * Implemented by InfinispanObjectStore to expose distributed locking
 * without requiring reflection. LRAService casts
 * StoreManager.getRecoveryStore() to this interface when HA mode is active.
 */
public interface HAMetadataStore {

    boolean tryLock(Uid uid) throws ObjectStoreException;

    void releaseLock(Uid uid) throws ObjectStoreException;
}
