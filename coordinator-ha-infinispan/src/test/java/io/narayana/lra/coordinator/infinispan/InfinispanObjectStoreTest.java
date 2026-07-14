/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.infinispan;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.arjuna.exceptions.ObjectStoreException;
import com.arjuna.ats.arjuna.objectstore.StateStatus;
import com.arjuna.ats.arjuna.state.InputObjectState;
import com.arjuna.ats.arjuna.state.OutputObjectState;
import io.narayana.lra.coordinator.internal.StoreUnavailableException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.infinispan.Cache;
import org.infinispan.CacheCollection;
import org.infinispan.CacheSet;
import org.infinispan.commons.CacheException;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InfinispanObjectStore}.
 *
 * <p>
 * Uses a real embedded LOCAL (non-clustered) Infinispan cache so that
 * every test exercises actual cache semantics without needing a JGroups
 * cluster. Static state is reset in {@link #tearDown()} via
 * {@link InfinispanObjectStore#setCache(Cache)} to prevent test pollution.
 * </p>
 *
 * <p>
 * Covers:
 * </p>
 * <ul>
 * <li>Configurable lock TTL with 10 s floor</li>
 * <li>Node-id derivation order</li>
 * <li>{@code allObjUids} key filtering and snapshot semantics</li>
 * <li>{@link StoreUnavailableException} raised on {@link CacheException} (direct path)</li>
 * <li>{@link ObjectStoreException} raised when cache is not initialised</li>
 * <li>Core CRUD: write/read/remove/allObjUids/allTypes/currentState</li>
 * </ul>
 */
class InfinispanObjectStoreTest {

    private static final String TYPE = "/StateManager/BasicAction/LongRunningAction";

    private EmbeddedCacheManager cacheManager;
    private InfinispanObjectStore store;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @BeforeEach
    void setUp() {
        GlobalConfigurationBuilder globalConfig = new GlobalConfigurationBuilder();
        globalConfig.nonClusteredDefault();
        cacheManager = new DefaultCacheManager(globalConfig.build());

        ConfigurationBuilder cb = new ConfigurationBuilder();
        // LOCAL mode (non-clustered) — no transport or partition-handling needed for tests
        cacheManager.defineConfiguration("lra-objectstore", cb.build());
        Cache<String, byte[]> cache = cacheManager.getCache("lra-objectstore");
        InfinispanObjectStore.setCache(cache);

        store = new InfinispanObjectStore();
    }

    @AfterEach
    void tearDown() {
        InfinispanObjectStore.setCache(null);
        if (cacheManager != null) {
            cacheManager.stop();
            cacheManager = null;
        }
    }

    // -----------------------------------------------------------------------
    // TASK-SA-02: getNodeId() derivation
    // -----------------------------------------------------------------------

    @Test
    void getNodeId_systemPropertyTakesPrecedence() {
        System.setProperty("lra.coordinator.node.id", "test-node-1");
        try {
            assertEquals("test-node-1", InfinispanObjectStore.getNodeId());
        } finally {
            System.clearProperty("lra.coordinator.node.id");
        }
    }

    @Test
    void getNodeId_fallsThroughToNonEmptyValue() {
        // Without the system property the method still returns a non-empty, non-"unknown" string.
        System.clearProperty("lra.coordinator.node.id");
        String id = InfinispanObjectStore.getNodeId();
        assertNotNull(id);
        assertFalse(id.isEmpty(), "node-id must never be empty");
        assertNotEquals("unknown", id, "legacy 'unknown' fallback must not be used");
    }

    /**
     * TS-NODEID-03 — {@code getNodeId()} must be stable within the same JVM invocation.
     * Spec §8.1: "MUST be stable across restarts of the same logical node".
     */
    @Test
    void getNodeId_isStableWithinSameJvmProcess() {
        System.clearProperty("lra.coordinator.node.id");
        String first = InfinispanObjectStore.getNodeId();
        String second = InfinispanObjectStore.getNodeId();
        assertEquals(first, second, "getNodeId() must return the same value on consecutive calls");
    }

    // -----------------------------------------------------------------------
    // TASK-SA-01: getLockTtlSeconds() floor and config
    // -----------------------------------------------------------------------

    @Test
    void getLockTtlSeconds_defaultIs30() {
        System.clearProperty("lra.coordinator.lock.ttl.seconds");
        assertEquals(30, InfinispanObjectStore.getLockTtlSeconds());
    }

    @Test
    void getLockTtlSeconds_readsSystemProperty() {
        System.setProperty("lra.coordinator.lock.ttl.seconds", "60");
        try {
            assertEquals(60, InfinispanObjectStore.getLockTtlSeconds());
        } finally {
            System.clearProperty("lra.coordinator.lock.ttl.seconds");
        }
    }

    @Test
    void getLockTtlSeconds_enforcesMinimumFloor() {
        System.setProperty("lra.coordinator.lock.ttl.seconds", "5");
        try {
            assertEquals(10, InfinispanObjectStore.getLockTtlSeconds(),
                    "TTL below minimum must be clamped to 10 s");
        } finally {
            System.clearProperty("lra.coordinator.lock.ttl.seconds");
        }
    }

    @Test
    void getLockTtlSeconds_invalidValueFallsBackToDefault() {
        System.setProperty("lra.coordinator.lock.ttl.seconds", "not-a-number");
        try {
            assertEquals(30, InfinispanObjectStore.getLockTtlSeconds());
        } finally {
            System.clearProperty("lra.coordinator.lock.ttl.seconds");
        }
    }

    /**
     * TS-LOCK-08 — a configured value exactly equal to the 10 s floor must be accepted
     * without clamping. Spec §14.2 "MUST be ≥ 10 seconds".
     */
    @Test
    void getLockTtlSeconds_atFloor_isNotClamped() {
        System.setProperty("lra.coordinator.lock.ttl.seconds", "10");
        try {
            assertEquals(10, InfinispanObjectStore.getLockTtlSeconds(),
                    "value exactly at the floor must be returned unchanged");
        } finally {
            System.clearProperty("lra.coordinator.lock.ttl.seconds");
        }
    }

    // -----------------------------------------------------------------------
    // Exception mapping: null-cache → ObjectStoreException,
    //                    CacheException → StoreUnavailableException
    // -----------------------------------------------------------------------

    @Test
    void writeCommitted_nullCache_throwsObjectStoreException() {
        InfinispanObjectStore.setCache(null);
        InfinispanObjectStore nullStore = new InfinispanObjectStore();
        assertThrows(ObjectStoreException.class,
                () -> nullStore.write_committed(new Uid(), TYPE, new OutputObjectState()));
    }

    /**
     * Verifies that a {@link CacheException} thrown by any cache operation is
     * converted to {@link StoreUnavailableException} (the 503 branch), not a
     * plain {@link ObjectStoreException} (the 500 branch).
     *
     * <p>
     * Uses an anonymous {@link Cache} delegate that throws
     * {@link CacheException} on every mutating method, without requiring Mockito.
     * </p>
     */
    @Test
    void cacheException_mapsToStoreUnavailableException() throws Exception {
        Cache<String, byte[]> failingCache = new FailingCache();
        InfinispanObjectStore.setCache(failingCache);
        InfinispanObjectStore failStore = new InfinispanObjectStore();

        assertThrows(StoreUnavailableException.class,
                () -> failStore.write_committed(new Uid(), TYPE, new OutputObjectState()),
                "write_committed must surface CacheException as StoreUnavailableException");

        assertThrows(StoreUnavailableException.class,
                () -> failStore.read_committed(new Uid(), TYPE),
                "read_committed must surface CacheException as StoreUnavailableException");

        assertThrows(StoreUnavailableException.class,
                () -> failStore.remove_committed(new Uid(), TYPE),
                "remove_committed must surface CacheException as StoreUnavailableException");

        assertThrows(StoreUnavailableException.class,
                () -> failStore.allObjUids(TYPE, new InputObjectState()),
                "allObjUids must surface CacheException as StoreUnavailableException");

        assertThrows(StoreUnavailableException.class,
                () -> failStore.tryLock(new Uid()),
                "tryLock must surface CacheException as StoreUnavailableException");
    }

    /**
     * TS-AVAIL-02 — Infinispan {@code AvailabilityException} (thrown on minority partition
     * with {@code DENY_READ_WRITES}) must be mapped to {@link StoreUnavailableException},
     * not a plain {@link ObjectStoreException}.
     * Spec §9.4: "minority partition MUST refuse all write operations … return HTTP 503".
     */
    @Test
    void availabilityException_mapsToStoreUnavailableException() {
        // AvailabilityException is a subclass of CacheException; it is the specific
        // signal raised by Infinispan when DENY_READ_WRITES blocks an operation on a
        // minority partition. We inject it via a targeted cache stub.
        Cache<String, byte[]> partitionedCache = new PartitionFailingCache();
        InfinispanObjectStore.setCache(partitionedCache);
        InfinispanObjectStore failStore = new InfinispanObjectStore();

        StoreUnavailableException ex = assertThrows(StoreUnavailableException.class,
                () -> failStore.write_committed(new Uid(), TYPE, new OutputObjectState()),
                "AvailabilityException must surface as StoreUnavailableException (HTTP 503 path)");
        assertNotNull(ex.getCause(), "cause must be preserved for diagnostics");
        assertInstanceOf(org.infinispan.partitionhandling.AvailabilityException.class, ex.getCause());
    }

    /**
     * TS-AVAIL-03 — A non-{@link CacheException} from the cache must NOT produce a
     * {@link StoreUnavailableException}; it must produce a plain {@link ObjectStoreException}
     * so that callers can distinguish transient 503 from permanent 500.
     * TASK-SA-04 completion criterion.
     */
    @Test
    void nonCacheException_mapsToPlainObjectStoreException() {
        Cache<String, byte[]> illegalArgCache = new IllegalArgFailingCache();
        InfinispanObjectStore.setCache(illegalArgCache);
        InfinispanObjectStore failStore = new InfinispanObjectStore();

        ObjectStoreException ex = assertThrows(ObjectStoreException.class,
                () -> failStore.write_committed(new Uid(), TYPE, new OutputObjectState()),
                "non-CacheException must produce ObjectStoreException, not StoreUnavailableException");
        assertFalse(ex instanceof StoreUnavailableException,
                "must not be classified as store-unavailable (that is the 503 path)");
    }

    // -----------------------------------------------------------------------
    // Core CRUD — write / read / remove
    // -----------------------------------------------------------------------

    @Test
    void writeAndReadCommitted_roundTrips() throws Exception {
        Uid uid = new Uid();
        OutputObjectState out = new OutputObjectState(uid, TYPE);
        out.packString("hello-lra");

        assertTrue(store.write_committed(uid, TYPE, out));

        InputObjectState in = store.read_committed(uid, TYPE);
        assertNotNull(in, "written entry must be readable");
        assertEquals("hello-lra", in.unpackString());
    }

    @Test
    void readCommitted_missingKey_returnsNull() throws Exception {
        assertNull(store.read_committed(new Uid(), TYPE));
    }

    @Test
    void removeCommitted_existingKey_returnsTrue() throws Exception {
        Uid uid = new Uid();
        store.write_committed(uid, TYPE, new OutputObjectState(uid, TYPE));
        assertTrue(store.remove_committed(uid, TYPE));
    }

    @Test
    void removeCommitted_missingKey_returnsFalse() throws Exception {
        assertFalse(store.remove_committed(new Uid(), TYPE));
    }

    // -----------------------------------------------------------------------
    // TASK-SA-03: allObjUids key filtering
    // -----------------------------------------------------------------------

    @Test
    void allObjUids_returnsOnlyMatchingType() throws Exception {
        Uid uid1 = new Uid();
        Uid uid2 = new Uid();
        String otherType = "/SomeOtherType";

        store.write_committed(uid1, TYPE, new OutputObjectState(uid1, TYPE));
        store.write_committed(uid2, otherType, new OutputObjectState(uid2, otherType));

        InputObjectState found = new InputObjectState();
        assertTrue(store.allObjUids(TYPE, found));

        // Drain the packed UIDs
        java.util.Set<String> uids = new java.util.HashSet<>();
        Uid u;
        while (!(u = com.arjuna.ats.internal.arjuna.common.UidHelper.unpackFrom(found)).equals(Uid.nullUid())) {
            uids.add(u.stringForm());
        }

        assertTrue(uids.contains(uid1.stringForm()), "uid1 must be listed");
        assertFalse(uids.contains(uid2.stringForm()), "uid2 of different type must not appear");
    }

    @Test
    void allObjUids_excludesLockKeys() throws Exception {
        Uid uid = new Uid();
        store.write_committed(uid, TYPE, new OutputObjectState(uid, TYPE));
        // Acquire a lock — this writes a lock/ key
        store.tryLock(uid);

        InputObjectState found = new InputObjectState();
        store.allObjUids(TYPE, found);

        java.util.Set<String> uids = new java.util.HashSet<>();
        Uid u;
        while (!(u = com.arjuna.ats.internal.arjuna.common.UidHelper.unpackFrom(found)).equals(Uid.nullUid())) {
            uids.add(u.stringForm());
        }
        // lock/ key must never appear
        for (String id : uids) {
            assertFalse(id.startsWith("lock/"), "lock key must not appear in allObjUids");
        }

        store.releaseLock(uid);
    }

    /**
     * TS-ENUM-03 — {@code allObjUids} must not return entries stored under the
     * {@code failed/} key prefix (spec §13.4: "failed-LRA area MUST have a distinct key prefix;
     * recovery scans do not re-process them").
     */
    @Test
    void allObjUids_excludesFailedPrefixedEntries() throws Exception {
        // Write one normal active LRA entry
        Uid activeUid = new Uid();
        store.write_committed(activeUid, TYPE, new OutputObjectState(activeUid, TYPE));

        // Directly insert a failed-area sentinel into the cache (simulates the
        // move-to-failed-area operation that exhausted-recovery performs).
        Cache<String, byte[]> cache = cacheManager.getCache("lra-objectstore");
        Uid failedUid = new Uid();
        cache.put("failed/" + failedUid.fileStringForm(), new byte[] { 1 });

        InputObjectState found = new InputObjectState();
        assertTrue(store.allObjUids(TYPE, found));

        java.util.Set<String> uids = new java.util.HashSet<>();
        Uid u;
        while (!(u = com.arjuna.ats.internal.arjuna.common.UidHelper.unpackFrom(found)).equals(Uid.nullUid())) {
            uids.add(u.stringForm());
        }

        assertTrue(uids.contains(activeUid.stringForm()), "active UID must be returned");
        for (String id : uids) {
            assertFalse(id.startsWith("failed/"), "failed/ sentinel must not appear in allObjUids results");
        }
    }

    /**
     * TS-ENUM-04 — {@code allObjUids} on an empty cache must return {@code true} and pack
     * only the terminating {@link Uid#nullUid()} (spec §9.3 enumerate semantics with empty store).
     */
    @Test
    void allObjUids_emptyCache_returnsTerminatorOnly() throws Exception {
        InputObjectState found = new InputObjectState();
        assertTrue(store.allObjUids(TYPE, found), "allObjUids must succeed on an empty cache");

        // The first (and only) unpacked UID must be the null-UID sentinel.
        Uid first = com.arjuna.ats.internal.arjuna.common.UidHelper.unpackFrom(found);
        assertEquals(Uid.nullUid(), first, "first unpacked UID on empty store must be Uid.nullUid()");
    }

    // -----------------------------------------------------------------------
    // Distributed locking — tryLock / releaseLock
    // -----------------------------------------------------------------------

    @Test
    void tryLock_secondAcquireReturnsFalse() throws Exception {
        System.setProperty("lra.coordinator.node.id", "node-A");
        try {
            Uid uid = new Uid();
            assertTrue(store.tryLock(uid), "first tryLock must succeed");
            assertFalse(store.tryLock(uid), "second tryLock while held must fail");
            store.releaseLock(uid);
        } finally {
            System.clearProperty("lra.coordinator.node.id");
        }
    }

    @Test
    void releaseLock_byOwner_allowsReacquire() throws Exception {
        System.setProperty("lra.coordinator.node.id", "node-B");
        try {
            Uid uid = new Uid();
            assertTrue(store.tryLock(uid));
            store.releaseLock(uid);
            assertTrue(store.tryLock(uid), "lock must be re-acquirable after release");
            store.releaseLock(uid);
        } finally {
            System.clearProperty("lra.coordinator.node.id");
        }
    }

    /**
     * TS-LOCK-04 — {@code releaseLock} by a node that does not own the lock must be a
     * no-op: the lock entry must remain unchanged.
     * Spec §14.2: "remove the lock entry only if its current value equals the releasing node's ID".
     */
    @Test
    void releaseLock_byNonOwner_doesNotRemoveLock() throws Exception {
        Uid uid = new Uid();
        System.setProperty("lra.coordinator.node.id", "original-owner");
        try {
            assertTrue(store.tryLock(uid));

            System.setProperty("lra.coordinator.node.id", "interloper");
            store.releaseLock(uid); // no-op expected

            Cache<String, byte[]> cache = cacheManager.getCache("lra-objectstore");
            assertTrue(cache.containsKey("lock/" + uid.fileStringForm()),
                    "non-owner releaseLock must leave the lock entry intact");
        } finally {
            System.setProperty("lra.coordinator.node.id", "original-owner");
            store.releaseLock(uid); // proper cleanup
            System.clearProperty("lra.coordinator.node.id");
        }
    }

    /**
     * TS-LOCK-10 — the value stored in the lock entry must be the current node ID
     * encoded as UTF-8 bytes. This verifies that the three-step derivation (spec §8.1)
     * propagates end-to-end into the distributed lock entry.
     * Spec §9.2: "Value: the node ID of the lock holder (UTF-8 string)".
     */
    @Test
    void tryLock_storesNodeIdAsBytesInLockEntry() throws Exception {
        System.setProperty("lra.coordinator.node.id", "coord-verify");
        try {
            Uid uid = new Uid();
            assertTrue(store.tryLock(uid));

            Cache<String, byte[]> cache = cacheManager.getCache("lra-objectstore");
            byte[] raw = cache.get("lock/" + uid.fileStringForm());
            assertNotNull(raw, "lock entry must exist in cache after tryLock");

            String storedId = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("coord-verify", storedId,
                    "lock entry value must decode to the current node ID");
            store.releaseLock(uid);
        } finally {
            System.clearProperty("lra.coordinator.node.id");
        }
    }

    // -----------------------------------------------------------------------
    // currentState / allTypes
    // -----------------------------------------------------------------------

    @Test
    void currentState_committedAfterWrite() throws Exception {
        Uid uid = new Uid();
        store.write_committed(uid, TYPE, new OutputObjectState(uid, TYPE));
        assertEquals(StateStatus.OS_COMMITTED, store.currentState(uid, TYPE));
    }

    @Test
    void currentState_unknownForMissingKey() throws Exception {
        assertEquals(StateStatus.OS_UNKNOWN, store.currentState(new Uid(), TYPE));
    }

    @Test
    void allTypes_excludesLockAndFailedPrefixes() throws Exception {
        Uid uid = new Uid();
        store.write_committed(uid, TYPE, new OutputObjectState(uid, TYPE));
        store.tryLock(uid); // adds lock/ key

        InputObjectState foundTypes = new InputObjectState();
        assertTrue(store.allTypes(foundTypes));

        java.util.Set<String> types = new java.util.HashSet<>();
        String t;
        while (!(t = foundTypes.unpackString()).isEmpty()) {
            types.add(t);
        }

        assertFalse(types.stream().anyMatch(s -> s.startsWith("lock/")),
                "lock/ prefix must not appear in allTypes");
        assertFalse(types.stream().anyMatch(s -> s.startsWith("failed/")),
                "failed/ prefix must not appear in allTypes");
        assertTrue(types.contains(TYPE),
                "written TYPE must appear in allTypes");

        store.releaseLock(uid);
    }

    // -----------------------------------------------------------------------
    // Lifecycle helpers
    // -----------------------------------------------------------------------

    @Test
    void getStoreName_returnsExpectedValue() {
        assertEquals("InfinispanObjectStore", store.getStoreName());
    }

    @Test
    void stopClearsCache() {
        store.stop();
        assertThrows(ObjectStoreException.class,
                () -> store.read_committed(new Uid(), TYPE));
    }

    @Test
    void sync_doesNotThrow() {
        assertDoesNotThrow(() -> store.sync());
    }
    // -----------------------------------------------------------------------
    // FailingCache — test double that throws CacheException on every operation
    // the store invokes, to verify the CacheException → StoreUnavailableException
    // mapping without requiring Mockito.
    // -----------------------------------------------------------------------

    /**
     * A minimal {@link Cache} implementation whose every operation called by
     * {@link InfinispanObjectStore} throws {@link CacheException}.
     *
     * <p>
     * Methods not called by the store return safe no-op defaults.
     * </p>
     */
    @SuppressWarnings("NullableProblems")
    private static class FailingCache implements Cache<String, byte[]> {

        private static final CacheException FAIL = new CacheException("simulated failure");

        // ---- Map operations called by the store ----

        @Override
        public byte[] put(String k, byte[] v) {
            throw FAIL;
        }

        @Override
        public byte[] put(String k, byte[] v, long l, TimeUnit u) {
            throw FAIL;
        }

        @Override
        public byte[] putIfAbsent(String k, byte[] v) {
            throw FAIL;
        }

        @Override
        public byte[] putIfAbsent(String k, byte[] v, long l, TimeUnit u) {
            throw FAIL;
        }

        @Override
        public byte[] get(Object k) {
            throw FAIL;
        }

        @Override
        public byte[] remove(Object k) {
            throw FAIL;
        }

        @Override
        public boolean remove(Object k, Object v) {
            throw FAIL;
        }

        @Override
        public boolean containsKey(Object k) {
            throw FAIL;
        }

        @Override
        public CacheSet<String> keySet() {
            throw FAIL;
        }

        // ---- Map operations not called by the store (no-op safe defaults) ----

        @Override
        public int size() {
            return 0;
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public boolean containsValue(Object v) {
            return false;
        }

        @Override
        public void putAll(Map<? extends String, ? extends byte[]> m) {
        }

        @Override
        public void clear() {
        }

        @Override
        public CacheCollection<byte[]> values() {
            return null;
        }

        @Override
        public CacheSet<Map.Entry<String, byte[]>> entrySet() {
            return null;
        }

        // ---- BasicCache extra put / replace / TTL overloads ----

        @Override
        public byte[] put(String k, byte[] v, long lifespan, TimeUnit lt, long maxIdle, TimeUnit mt) {
            return null;
        }

        @Override
        public byte[] putIfAbsent(String k, byte[] v, long l, TimeUnit lt, long m, TimeUnit mt) {
            return null;
        }

        @Override
        public void putAll(Map<? extends String, ? extends byte[]> m, long l, TimeUnit u) {
        }

        @Override
        public void putAll(Map<? extends String, ? extends byte[]> m, long l, TimeUnit lt, long ml, TimeUnit mt) {
        }

        @Override
        public byte[] replace(String k, byte[] v) {
            return null;
        }

        @Override
        public byte[] replace(String k, byte[] v, long l, TimeUnit u) {
            return null;
        }

        @Override
        public boolean replace(String k, byte[] ov, byte[] nv) {
            return false;
        }

        @Override
        public boolean replace(String k, byte[] ov, byte[] nv, long l, TimeUnit u) {
            return false;
        }

        @Override
        public byte[] replace(String k, byte[] v, long l, TimeUnit lt, long m, TimeUnit mt) {
            return null;
        }

        @Override
        public boolean replace(String k, byte[] ov, byte[] nv, long l, TimeUnit lt, long m, TimeUnit mt) {
            return false;
        }

        // ---- compute / merge overloads ----

        @Override
        public byte[] compute(String k, BiFunction<? super String, ? super byte[], ? extends byte[]> f) {
            return null;
        }

        @Override
        public byte[] compute(String k, BiFunction<? super String, ? super byte[], ? extends byte[]> f, long l, TimeUnit u) {
            return null;
        }

        @Override
        public byte[] compute(String k, BiFunction<? super String, ? super byte[], ? extends byte[]> f, long l, TimeUnit lt,
                long m, TimeUnit mt) {
            return null;
        }

        @Override
        public byte[] computeIfPresent(String k, BiFunction<? super String, ? super byte[], ? extends byte[]> f) {
            return null;
        }

        @Override
        public byte[] computeIfPresent(String k, BiFunction<? super String, ? super byte[], ? extends byte[]> f, long l,
                TimeUnit u) {
            return null;
        }

        @Override
        public byte[] computeIfPresent(String k, BiFunction<? super String, ? super byte[], ? extends byte[]> f, long l,
                TimeUnit lt, long m, TimeUnit mt) {
            return null;
        }

        @Override
        public byte[] computeIfAbsent(String k, Function<? super String, ? extends byte[]> f) {
            return null;
        }

        @Override
        public byte[] computeIfAbsent(String k, Function<? super String, ? extends byte[]> f, long l, TimeUnit u) {
            return null;
        }

        @Override
        public byte[] computeIfAbsent(String k, Function<? super String, ? extends byte[]> f, long l, TimeUnit lt, long m,
                TimeUnit mt) {
            return null;
        }

        @Override
        public byte[] merge(String k, byte[] v, BiFunction<? super byte[], ? super byte[], ? extends byte[]> f) {
            return null;
        }

        @Override
        public byte[] merge(String k, byte[] v, BiFunction<? super byte[], ? super byte[], ? extends byte[]> f, long l,
                TimeUnit u) {
            return null;
        }

        @Override
        public byte[] merge(String k, byte[] v, BiFunction<? super byte[], ? super byte[], ? extends byte[]> f, long l,
                TimeUnit lt, long m, TimeUnit mt) {
            return null;
        }

        // ---- Async variants ----

        @Override
        public CompletableFuture<byte[]> putAsync(String k, byte[] v) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<byte[]> putAsync(String k, byte[] v, long l, TimeUnit u) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<byte[]> putAsync(String k, byte[] v, long l, TimeUnit lt, long m, TimeUnit mt) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> putAllAsync(Map<? extends String, ? extends byte[]> m) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> putAllAsync(Map<? extends String, ? extends byte[]> m, long l, TimeUnit u) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> putAllAsync(Map<? extends String, ? extends byte[]> m, long l, TimeUnit lt, long m2,
                TimeUnit mt) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> clearAsync() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Long> sizeAsync() {
            return CompletableFuture.completedFuture(0L);
        }

        @Override
        public CompletableFuture<byte[]> putIfAbsentAsync(String k, byte[] v) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<byte[]> putIfAbsentAsync(String k, byte[] v, long l, TimeUnit u) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<byte[]> putIfAbsentAsync(String k, byte[] v, long l, TimeUnit lt, long m, TimeUnit mt) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<byte[]> removeAsync(Object k) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Boolean> removeAsync(Object k, Object v) {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public CompletableFuture<byte[]> replaceAsync(String k, byte[] v) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<byte[]> replaceAsync(String k, byte[] v, long l, TimeUnit u) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<byte[]> replaceAsync(String k, byte[] v, long l, TimeUnit lt, long m, TimeUnit mt) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Boolean> replaceAsync(String k, byte[] ov, byte[] nv) {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public CompletableFuture<Boolean> replaceAsync(String k, byte[] ov, byte[] nv, long l, TimeUnit u) {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public CompletableFuture<Boolean> replaceAsync(String k, byte[] ov, byte[] nv, long l, TimeUnit lt, long m,
                TimeUnit mt) {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public CompletableFuture<byte[]> getAsync(String k) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<byte[]> computeAsync(String k,
                BiFunction<? super String, ? super byte[], ? extends byte[]> f) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<byte[]> computeAsync(String k, BiFunction<? super String, ? super byte[], ? extends byte[]> f,
                long l, TimeUnit u) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<byte[]> computeAsync(String k, BiFunction<? super String, ? super byte[], ? extends byte[]> f,
                long l, TimeUnit lt, long m, TimeUnit mt) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<byte[]> computeIfAbsentAsync(String k, Function<? super String, ? extends byte[]> f) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<byte[]> computeIfAbsentAsync(String k, Function<? super String, ? extends byte[]> f, long l,
                TimeUnit u) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<byte[]> computeIfAbsentAsync(String k, Function<? super String, ? extends byte[]> f, long l,
                TimeUnit lt, long m, TimeUnit mt) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<byte[]> computeIfPresentAsync(String k,
                BiFunction<? super String, ? super byte[], ? extends byte[]> f) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<byte[]> computeIfPresentAsync(String k,
                BiFunction<? super String, ? super byte[], ? extends byte[]> f, long l, TimeUnit u) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<byte[]> computeIfPresentAsync(String k,
                BiFunction<? super String, ? super byte[], ? extends byte[]> f, long l, TimeUnit lt, long m, TimeUnit mt) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<byte[]> mergeAsync(String k, byte[] v,
                BiFunction<? super byte[], ? super byte[], ? extends byte[]> f) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<byte[]> mergeAsync(String k, byte[] v,
                BiFunction<? super byte[], ? super byte[], ? extends byte[]> f, long l, TimeUnit u) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<byte[]> mergeAsync(String k, byte[] v,
                BiFunction<? super byte[], ? super byte[], ? extends byte[]> f, long l, TimeUnit lt, long m, TimeUnit mt) {
            return CompletableFuture.completedFuture(null);
        }

        // ---- Cache-specific methods ----

        @Override
        public void putForExternalRead(String k, byte[] v) {
        }

        @Override
        public void putForExternalRead(String k, byte[] v, long l, TimeUnit u) {
        }

        @Override
        public void putForExternalRead(String k, byte[] v, long l, TimeUnit lt, long m, TimeUnit mt) {
        }

        @Override
        public void evict(String k) {
        }

        @Override
        public org.infinispan.configuration.cache.Configuration getCacheConfiguration() {
            return null;
        }

        @Override
        public org.infinispan.manager.EmbeddedCacheManager getCacheManager() {
            return null;
        }

        @Override
        public org.infinispan.AdvancedCache<String, byte[]> getAdvancedCache() {
            return null;
        }

        @Override
        public org.infinispan.lifecycle.ComponentStatus getStatus() {
            return null;
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public String getName() {
            return "FailingCache";
        }

        @Override
        public String getVersion() {
            return "0";
        }

        // ---- Querying ----

        @Override
        public <T> org.infinispan.commons.api.query.Query<T> query(String q) {
            return null;
        }

        @Override
        public org.infinispan.commons.api.query.ContinuousQuery<String, byte[]> continuousQuery() {
            return null;
        }

        // ---- Batching ----

        @Override
        public boolean startBatch() {
            return false;
        }

        @Override
        public void endBatch(boolean commit) {
        }

        // ---- Listeners ----

        @Override
        public java.util.concurrent.CompletionStage<Void> addListenerAsync(Object listener) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> removeListenerAsync(Object listener) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <C> java.util.concurrent.CompletionStage<Void> addListenerAsync(Object listener,
                org.infinispan.notifications.cachelistener.filter.CacheEventFilter<? super String, ? super byte[]> filter,
                org.infinispan.notifications.cachelistener.filter.CacheEventConverter<? super String, ? super byte[], C> converter) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <C> java.util.concurrent.CompletionStage<Void> addFilteredListenerAsync(Object listener,
                org.infinispan.notifications.cachelistener.filter.CacheEventFilter<? super String, ? super byte[]> filter,
                org.infinispan.notifications.cachelistener.filter.CacheEventConverter<? super String, ? super byte[], C> converter,
                Set<Class<? extends java.lang.annotation.Annotation>> annotations) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <C> java.util.concurrent.CompletionStage<Void> addStorageFormatFilteredListenerAsync(Object listener,
                org.infinispan.notifications.cachelistener.filter.CacheEventFilter<? super String, ? super byte[]> filter,
                org.infinispan.notifications.cachelistener.filter.CacheEventConverter<? super String, ? super byte[], C> converter,
                Set<Class<? extends java.lang.annotation.Annotation>> annotations) {
            return CompletableFuture.completedFuture(null);
        }

    }

    // -----------------------------------------------------------------------
    // PartitionFailingCache — throws AvailabilityException on put (TS-AVAIL-02)
    // -----------------------------------------------------------------------

    /**
     * Cache stub whose {@code put} throws {@link org.infinispan.partitionhandling.AvailabilityException}
     * to simulate a DENY_READ_WRITES minority partition response.
     * All other methods delegate to {@link FailingCache} so they also throw {@link CacheException}
     * — this keeps the stub minimal.
     */
    @SuppressWarnings("NullableProblems")
    private static class PartitionFailingCache extends FailingCache {

        @Override
        public byte[] put(String k, byte[] v) {
            throw new org.infinispan.partitionhandling.AvailabilityException(
                    "simulated minority partition");
        }

        @Override
        public byte[] put(String k, byte[] v, long l, TimeUnit u) {
            throw new org.infinispan.partitionhandling.AvailabilityException(
                    "simulated minority partition");
        }
    }

    // -----------------------------------------------------------------------
    // IllegalArgFailingCache — throws IllegalArgumentException on put (TS-AVAIL-03)
    // -----------------------------------------------------------------------

    /**
     * Cache stub whose {@code put} throws {@link IllegalArgumentException} (not a
     * {@link CacheException}) to verify that the adaptor does NOT produce
     * {@link StoreUnavailableException} for non-availability errors.
     */
    @SuppressWarnings("NullableProblems")
    private static class IllegalArgFailingCache extends FailingCache {

        @Override
        public byte[] put(String k, byte[] v) {
            throw new IllegalArgumentException("simulated non-cache error");
        }

        @Override
        public byte[] put(String k, byte[] v, long l, TimeUnit u) {
            throw new IllegalArgumentException("simulated non-cache error");
        }
    }
}
