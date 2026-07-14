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
import io.narayana.lra.coordinator.internal.StoreUnavailableException;
import io.narayana.lra.logging.LRALogger;
import java.io.SyncFailedException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.infinispan.Cache;
import org.infinispan.commons.CacheException;

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
 *
 * <p>
 * Infinispan cache failures ({@link CacheException}, which includes
 * {@code AvailabilityException} for degraded partitions) are converted to
 * {@link StoreUnavailableException} so callers can distinguish a
 * transient 503-class failure from a hard 500-class store error.
 * </p>
 */
public class InfinispanObjectStore implements ObjectStoreAPI, HAMetadataStore {

    /** Minimum TTL for distributed locks, in seconds. */
    private static final int LOCK_TTL_MIN_SECONDS = 10;

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

    /**
     * Returns a stable, cluster-unique identifier for this coordinator node.
     *
     * <p>
     * Resolution order:
     * </p>
     * <ol>
     * <li>System property {@code lra.coordinator.node.id}</li>
     * <li>Environment variable {@code HOSTNAME}</li>
     * <li>{@code "lra-coord-" + current PID} (always available on Java 9+)</li>
     * </ol>
     */
    static String getNodeId() {
        String id = System.getProperty("lra.coordinator.node.id");
        if (id != null && !id.isEmpty()) {
            return id;
        }
        id = System.getenv("HOSTNAME");
        if (id != null && !id.isEmpty()) {
            return id;
        }
        return "lra-coord-" + ProcessHandle.current().pid();
    }

    /**
     * Returns the lock TTL in seconds, reading
     * {@code lra.coordinator.lock.ttl.seconds} (default 30, minimum 10).
     */
    static int getLockTtlSeconds() {
        int ttl;
        try {
            ttl = Integer.parseInt(
                    System.getProperty("lra.coordinator.lock.ttl.seconds", "30"));
        } catch (NumberFormatException e) {
            ttl = 30;
        }
        return Math.max(ttl, LOCK_TTL_MIN_SECONDS);
    }

    @Override
    public boolean write_committed(Uid uid, String type, OutputObjectState state)
            throws ObjectStoreException {
        try {
            cache().put(key(uid, type), state.buffer());
            return true;
        } catch (CacheException e) {
            throw new StoreUnavailableException("write_committed – store unavailable", e);
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
     * The lock entry has a TTL driven by
     * {@code lra.coordinator.lock.ttl.seconds} (default 30 s, minimum 10 s)
     * so stale locks from crashed nodes are automatically expelled.
     * </p>
     *
     * <p>
     * The lock is released by calling {@link #releaseLock(Uid)}.
     * </p>
     *
     * @return true if the lock was acquired, false if another node holds it
     * @throws StoreUnavailableException if the cache is in a degraded partition
     * @throws ObjectStoreException for any other store failure
     */
    @Override
    public boolean tryLock(Uid uid) throws ObjectStoreException {
        try {
            String lockKey = "lock/" + uid.fileStringForm();
            byte[] nodeValue = getNodeId().getBytes(StandardCharsets.UTF_8);
            // TTL is configurable via lra.coordinator.lock.ttl.seconds (min 10 s).
            byte[] existing = cache().putIfAbsent(lockKey, nodeValue,
                    getLockTtlSeconds(), TimeUnit.SECONDS);
            return existing == null;
        } catch (CacheException e) {
            throw new StoreUnavailableException("tryLock – store unavailable", e);
        } catch (Exception e) {
            throw new ObjectStoreException(e);
        }
    }

    /**
     * Releases the lock acquired by {@link #tryLock(Uid)}.
     * Only removes the lock if this node owns it (conditional remove).
     *
     * @throws StoreUnavailableException if the cache is in a degraded partition
     * @throws ObjectStoreException for any other store failure
     */
    @Override
    public void releaseLock(Uid uid) throws ObjectStoreException {
        try {
            String lockKey = "lock/" + uid.fileStringForm();
            byte[] nodeValue = getNodeId().getBytes(StandardCharsets.UTF_8);
            cache().remove(lockKey, nodeValue);
        } catch (CacheException e) {
            throw new StoreUnavailableException("releaseLock – store unavailable", e);
        } catch (Exception e) {
            throw new ObjectStoreException(e);
        }
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
        } catch (CacheException e) {
            throw new StoreUnavailableException("read_committed – store unavailable", e);
        } catch (Exception e) {
            throw new ObjectStoreException(e);
        }
    }

    @Override
    public boolean remove_committed(Uid uid, String type)
            throws ObjectStoreException {
        try {
            return cache().remove(key(uid, type)) != null;
        } catch (CacheException e) {
            throw new StoreUnavailableException("remove_committed – store unavailable", e);
        } catch (Exception e) {
            throw new ObjectStoreException(e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Iterates a point-in-time snapshot of the cache key set and returns every
     * UID whose key starts with the given type prefix. Taking a snapshot avoids
     * {@link java.util.ConcurrentModificationException} if concurrent writes
     * arrive during a recovery enumeration.
     * </p>
     *
     * <p>
     * The {@code matchState} parameter is accepted for API compatibility but
     * intentionally not filtered on: the backing {@code REPL_SYNC} cache has
     * always-committed semantics — every entry is considered committed. A DEBUG
     * log is emitted when a caller passes a state other than
     * {@link StateStatus#OS_UNKNOWN} so that unexpected usage is visible.
     * </p>
     */
    @Override
    public boolean allObjUids(String type, InputObjectState foundInstances, int matchState)
            throws ObjectStoreException {
        if (matchState != StateStatus.OS_UNKNOWN) {
            LRALogger.logger.debugf(
                    "InfinispanObjectStore.allObjUids: matchState=%d ignored — "
                            + "always-committed semantics; all entries returned",
                    matchState);
        }
        try {
            String prefix = type + "/";
            OutputObjectState buffer = new OutputObjectState();

            // Snapshot the key set to avoid ConcurrentModificationException
            // if writes arrive concurrently during a recovery scan.
            for (String k : new java.util.HashSet<>(cache().keySet())) {
                if (k.startsWith(prefix)) {
                    // The prefix check already excludes lock/ and failed/ namespaces.
                    String uidStr = k.substring(prefix.length());
                    UidHelper.packInto(new Uid(uidStr), buffer);
                }
            }

            UidHelper.packInto(Uid.nullUid(), buffer);
            foundInstances.setBuffer(buffer.buffer());
            return true;
        } catch (CacheException e) {
            throw new StoreUnavailableException("allObjUids – store unavailable", e);
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

            // Snapshot avoids ConcurrentModificationException during concurrent writes.
            for (String k : new java.util.HashSet<>(cache().keySet())) {
                if (k.startsWith("lock/") || k.startsWith("failed/")) {
                    continue; // skip distributed-lock and failed-LRA sentinel keys
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
        } catch (CacheException e) {
            throw new StoreUnavailableException("allTypes – store unavailable", e);
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
        } catch (CacheException e) {
            throw new StoreUnavailableException("currentState – store unavailable", e);
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
