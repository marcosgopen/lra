/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.infinispan;

import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.exceptions.ObjectStoreException;
import com.arjuna.ats.arjuna.objectstore.ObjectStoreAPI;
import com.arjuna.ats.arjuna.objectstore.StateStatus;
import com.arjuna.ats.arjuna.state.InputObjectState;
import com.arjuna.ats.arjuna.state.OutputObjectState;
import com.arjuna.ats.internal.arjuna.common.UidHelper;
import io.narayana.lra.coordinator.internal.HAMetadataStore;
import java.io.SyncFailedException;
import java.nio.charset.StandardCharsets;
import org.infinispan.Cache;

/**
 * ObjectStoreAPI implementation backed by a replicated Infinispan cache.
 *
 * <p>
 * Maps Narayana's ObjectStore operations directly to Infinispan cache
 * operations with no intermediate abstraction:
 * </p>
 * <ul>
 * <li>{@code write_committed(uid, type, state)} → {@code cache.put(key, bytes)}</li>
 * <li>{@code read_committed(uid, type)} → {@code cache.get(key)}</li>
 * <li>{@code remove_committed(uid, type)} → {@code cache.remove(key)}</li>
 * <li>{@code allObjUids(type)} → iterate {@code cache.keySet()} with type prefix</li>
 * </ul>
 *
 * <p>
 * Cache key format: {@code {type}/{uid}}, e.g.
 * {@code /StateManager/BasicAction/LongRunningAction/0_ffff7f000001_abc_123}.
 * </p>
 *
 * <p>
 * With a {@code REPL_SYNC} cache, all writes are synchronously replicated
 * to every cluster node. Any coordinator can {@code activate()} any LRA by
 * reading from its local cache replica.
 * </p>
 */
public class InfinispanObjectStore implements ObjectStoreAPI, HAMetadataStore {

    private static volatile Cache<String, byte[]> infinispanCache;

    public static void setCache(Cache<String, byte[]> cache) {
        infinispanCache = cache;
    }

    private Cache<String, byte[]> cache() throws ObjectStoreException {
        Cache<String, byte[]> c = infinispanCache;
        if (c == null) {
            throw new ObjectStoreException("InfinispanObjectStore: cache not initialized");
        }
        return c;
    }

    private static String key(Uid uid, String type) {
        return type + "/" + uid.fileStringForm();
    }

    @Override
    public boolean write_committed(Uid uid, String type, OutputObjectState state)
            throws ObjectStoreException {
        try {
            cache().put(key(uid, type), state.buffer());
            return true;
        } catch (Exception e) {
            throw new ObjectStoreException(e);
        }
    }

    /**
     * Tries to acquire an exclusive lock for the given LRA UID.
     * Uses {@code cache.putIfAbsent} on a lock key — if another node
     * already holds the lock, returns false.
     *
     * <p>
     * The lock is released by calling {@link #releaseLock(Uid)}.
     * </p>
     *
     * @return true if the lock was acquired, false if another node holds it
     */
    public boolean tryLock(Uid uid) throws ObjectStoreException {
        try {
            String lockKey = "lock/" + uid.fileStringForm();
            byte[] nodeValue = getNodeId().getBytes(StandardCharsets.UTF_8);
            byte[] existing = cache().putIfAbsent(lockKey, nodeValue,
                    30, java.util.concurrent.TimeUnit.SECONDS);
            return existing == null;
        } catch (Exception e) {
            throw new ObjectStoreException(e);
        }
    }

    /**
     * Releases the lock acquired by {@link #tryLock(Uid)}.
     * Only removes the lock if this node owns it.
     */
    public void releaseLock(Uid uid) throws ObjectStoreException {
        try {
            String lockKey = "lock/" + uid.fileStringForm();
            byte[] nodeValue = getNodeId().getBytes(StandardCharsets.UTF_8);
            cache().remove(lockKey, nodeValue);
        } catch (Exception e) {
            throw new ObjectStoreException(e);
        }
    }

    private static String getNodeId() {
        return System.getProperty("lra.coordinator.node.id", "unknown");
    }

    @Override
    public InputObjectState read_committed(Uid uid, String type)
            throws ObjectStoreException {
        try {
            byte[] data = cache().get(key(uid, type));
            if (data == null) {
                return null;
            }
            return new InputObjectState(uid, type, data);
        } catch (Exception e) {
            throw new ObjectStoreException(e);
        }
    }

    @Override
    public boolean remove_committed(Uid uid, String type)
            throws ObjectStoreException {
        try {
            return cache().remove(key(uid, type)) != null;
        } catch (Exception e) {
            throw new ObjectStoreException(e);
        }
    }

    @Override
    public boolean allObjUids(String type, InputObjectState foundInstances, int matchState)
            throws ObjectStoreException {
        try {
            String prefix = type + "/";
            OutputObjectState buffer = new OutputObjectState();

            for (String k : cache().keySet()) {
                if (k.startsWith(prefix)) {
                    String uidStr = k.substring(prefix.length());
                    if (uidStr.contains("/")) {
                        continue;
                    }
                    UidHelper.packInto(new Uid(uidStr), buffer);
                }
            }

            UidHelper.packInto(Uid.nullUid(), buffer);
            foundInstances.setBuffer(buffer.buffer());
            return true;
        } catch (Exception e) {
            throw new ObjectStoreException(e);
        }
    }

    @Override
    public boolean allObjUids(String type, InputObjectState foundInstances)
            throws ObjectStoreException {
        return allObjUids(type, foundInstances, StateStatus.OS_UNKNOWN);
    }

    @Override
    public boolean allTypes(InputObjectState foundTypes) throws ObjectStoreException {
        try {
            OutputObjectState buffer = new OutputObjectState();
            java.util.Set<String> types = new java.util.HashSet<>();

            for (String k : cache().keySet()) {
                if (k.startsWith("lock/")) {
                    continue; // distributed lock key, not a transaction type
                }
                int lastSlash = k.lastIndexOf('/');
                if (lastSlash > 0) {
                    types.add(k.substring(0, lastSlash));
                }
            }

            for (String type : types) {
                buffer.packString(type);
            }
            buffer.packString("");
            foundTypes.setBuffer(buffer.buffer());
            return true;
        } catch (Exception e) {
            throw new ObjectStoreException(e);
        }
    }

    @Override
    public int currentState(Uid uid, String type) throws ObjectStoreException {
        try {
            return cache().containsKey(key(uid, type))
                    ? StateStatus.OS_COMMITTED
                    : StateStatus.OS_UNKNOWN;
        } catch (Exception e) {
            throw new ObjectStoreException(e);
        }
    }

    @Override
    public boolean hide_state(Uid uid, String type) throws ObjectStoreException {
        return false;
    }

    @Override
    public boolean reveal_state(Uid uid, String type) throws ObjectStoreException {
        return false;
    }

    @Override
    public boolean isType(Uid uid, String type, int st) throws ObjectStoreException {
        return currentState(uid, type) == st;
    }

    @Override
    public boolean commit_state(Uid uid, String type) throws ObjectStoreException {
        return true;
    }

    @Override
    public InputObjectState read_uncommitted(Uid uid, String type)
            throws ObjectStoreException {
        return null;
    }

    @Override
    public boolean remove_uncommitted(Uid uid, String type)
            throws ObjectStoreException {
        return true;
    }

    @Override
    public boolean write_uncommitted(Uid uid, String type, OutputObjectState state)
            throws ObjectStoreException {
        return write_committed(uid, type, state);
    }

    @Override
    public boolean fullCommitNeeded() {
        return false;
    }

    @Override
    public String getStoreName() {
        return "InfinispanObjectStore";
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
        infinispanCache = null;
    }

    @Override
    public void sync() throws SyncFailedException, ObjectStoreException {
    }
}
