# Unit Test Scenarios — Distributed LRA Coordinator (State Store Adaptor Slice)

**Version:** 1.0  
**Status:** Draft  
**Source spec:** [distributed-lra-coordinator-spec.md](distributed-lra-coordinator-spec.md)  
**Source tasks:** [tasks.md](tasks.md)  
**Scope:** `InfinispanObjectStore` + `InfinispanConfiguration` (TASK-SA-01 through TASK-SA-06)

---

## How to Read This Document

Each scenario maps directly to one or more **acceptance criteria (AC-x.y)** or **conformance requirements (C-xx)** in the feature spec. The scenario ID encodes the area:

- `TS-LOCK-xx` — distributed locking (spec §14, §9.2)
- `TS-CRUD-xx` — core CRUD operations (spec §9.3)
- `TS-ENUM-xx` — enumeration / key filtering (spec §9.3, C-12)
- `TS-AVAIL-xx` — store-unavailability exception mapping (spec §9.4, §15.4)
- `TS-NODEID-xx` — node ID derivation (spec §8.1, §9.2)
- `TS-CFG-xx` — cache configuration correctness (spec §9.4, §9.5)

Test type abbreviations: **P** = positive, **N** = negative, **B** = boundary.

---

## Section 1 — Distributed Locking (`TS-LOCK`)

### TS-LOCK-01

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-LOCK-01 |
| **Source AC** | AC-9.4 — Lock exclusivity; spec §14.2 "MUST succeed only if no lock entry is currently present" |
| **Source tasks** | TASK-SA-01, TASK-SA-02, TASK-SA-06 |
| **Type** | Positive |
| **Description** | First `tryLock` on an unlocked UID succeeds |
| **Input** | Cache contains no entry under `lock/{uid}`. Call `tryLock(uid)`. |
| **Expected result** | Returns `true`. Cache entry `lock/{uid}` exists and its value decodes to the current node ID. |

---

### TS-LOCK-02

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-LOCK-02 |
| **Source AC** | AC-9.4 — Lock exclusivity; spec §14.2 CAS semantics |
| **Source tasks** | TASK-SA-06 |
| **Type** | Negative |
| **Description** | Second `tryLock` on an already-locked UID returns false |
| **Input** | `tryLock(uid)` called once successfully. Call `tryLock(uid)` a second time (same or different simulated node). |
| **Expected result** | Second call returns `false`. Lock entry is unchanged (still owned by the first caller). |

---

### TS-LOCK-03

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-LOCK-03 |
| **Source AC** | AC-9.5 — Lock TTL expiry; spec §14.2 "MUST carry a TTL" |
| **Source tasks** | TASK-SA-02, TASK-SA-06 |
| **Type** | Positive |
| **Description** | After `tryLock` + `releaseLock` by the owning node, the lock entry is absent |
| **Input** | `tryLock(uid)` returns `true`. Call `releaseLock(uid)` with the same node ID. |
| **Expected result** | `cache.containsKey("lock/{uid}")` returns `false`. |

---

### TS-LOCK-04

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-LOCK-04 |
| **Source AC** | spec §14.2 "remove only if its current value equals the releasing node's ID" |
| **Source tasks** | TASK-SA-02, TASK-SA-06 |
| **Type** | Negative |
| **Description** | `releaseLock` by a node that does not own the lock is a no-op |
| **Input** | Lock entry `lock/{uid}` exists with value `"node-A"`. Call `releaseLock(uid)` from a context where `getNodeId()` returns `"node-B"`. |
| **Expected result** | Lock entry `lock/{uid}` still exists with value `"node-A"`. No exception thrown. |

---

### TS-LOCK-05

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-LOCK-05 |
| **Source AC** | spec §14.2 "Default MUST be 30 seconds"; C-07 |
| **Source tasks** | TASK-SA-01 |
| **Type** | Positive |
| **Description** | `getLockTtlSeconds()` returns 30 when no system property is set |
| **Input** | System property `lra.coordinator.lock.ttl.seconds` is absent. Call `InfinispanObjectStore.getLockTtlSeconds()`. |
| **Expected result** | Returns `30`. |

---

### TS-LOCK-06

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-LOCK-06 |
| **Source AC** | spec §14.2 "MUST be configurable"; spec §16 property `lra.coordinator.lock.ttl.seconds` |
| **Source tasks** | TASK-SA-01 |
| **Type** | Positive |
| **Description** | `getLockTtlSeconds()` reads the configured value when property is set |
| **Input** | System property `lra.coordinator.lock.ttl.seconds=60`. Call `getLockTtlSeconds()`. |
| **Expected result** | Returns `60`. |

---

### TS-LOCK-07

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-LOCK-07 |
| **Source AC** | spec §14.2 "MUST be ≥ 10 seconds"; tasks.md TASK-SA-01 completion criterion |
| **Source tasks** | TASK-SA-01 |
| **Type** | Boundary |
| **Description** | `getLockTtlSeconds()` enforces a minimum of 10 s when configured value is below floor |
| **Input** | System property `lra.coordinator.lock.ttl.seconds=5`. Call `getLockTtlSeconds()`. |
| **Expected result** | Returns `10` (minimum enforced, not the raw property value). |

---

### TS-LOCK-08

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-LOCK-08 |
| **Source AC** | spec §14.2 minimum TTL; boundary at exactly the floor |
| **Source tasks** | TASK-SA-01 |
| **Type** | Boundary |
| **Description** | `getLockTtlSeconds()` accepts exactly 10 s without clamping |
| **Input** | System property `lra.coordinator.lock.ttl.seconds=10`. Call `getLockTtlSeconds()`. |
| **Expected result** | Returns `10` (value is at the floor; no clamping applied). |

---

### TS-LOCK-09

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-LOCK-09 |
| **Source AC** | spec §14.2 TTL configurability; robustness |
| **Source tasks** | TASK-SA-01 |
| **Type** | Negative |
| **Description** | `getLockTtlSeconds()` falls back to default when property is not a valid integer |
| **Input** | System property `lra.coordinator.lock.ttl.seconds=not-a-number`. Call `getLockTtlSeconds()`. |
| **Expected result** | Returns `30` (default, no exception propagated). |

---

### TS-LOCK-10

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-LOCK-10 |
| **Source AC** | AC-9.4 — lock exclusivity; spec §14.2 node ID stored in lock value |
| **Source tasks** | TASK-SA-02 |
| **Type** | Positive |
| **Description** | Lock value bytes decode to the current node ID |
| **Input** | System property `lra.coordinator.node.id=coord-1`. Call `tryLock(uid)`. |
| **Expected result** | `new String(cache.get("lock/{uid}"), StandardCharsets.UTF_8)` equals `"coord-1"`. |

---

## Section 2 — Node ID Derivation (`TS-NODEID`)

### TS-NODEID-01

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-NODEID-01 |
| **Source AC** | spec §8.1 "unique within the cluster"; C-01; TASK-SA-02 completion criterion |
| **Source tasks** | TASK-SA-02 |
| **Type** | Positive |
| **Description** | System property `lra.coordinator.node.id` takes highest precedence |
| **Input** | System property `lra.coordinator.node.id=test-node-1`. Call `InfinispanObjectStore.getNodeId()`. |
| **Expected result** | Returns `"test-node-1"`. |

---

### TS-NODEID-02

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-NODEID-02 |
| **Source AC** | spec §8.1 node ID stability; TASK-SA-02 three-step derivation |
| **Source tasks** | TASK-SA-02 |
| **Type** | Positive |
| **Description** | Fallback chain produces a non-empty, non-`"unknown"` value when no property is set |
| **Input** | System property `lra.coordinator.node.id` absent. Call `InfinispanObjectStore.getNodeId()`. |
| **Expected result** | Returns a non-null, non-empty string that is not `"unknown"`. |

---

### TS-NODEID-03

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-NODEID-03 |
| **Source AC** | spec §8.1 "MUST NOT be a randomly generated ID that changes on every restart"; TASK-SA-02 |
| **Source tasks** | TASK-SA-02 |
| **Type** | Positive |
| **Description** | `getNodeId()` is stable within the same JVM invocation |
| **Input** | Call `InfinispanObjectStore.getNodeId()` twice consecutively with no property changes. |
| **Expected result** | Both calls return the same string value. |

---

## Section 3 — Core CRUD Operations (`TS-CRUD`)

### TS-CRUD-01

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-CRUD-01 |
| **Source AC** | spec §9.3 write + read operations; AC-9.1 "LRA record is readable" |
| **Source tasks** | TASK-SA-06 |
| **Type** | Positive |
| **Description** | `write_committed` + `read_committed` round-trip returns the original bytes |
| **Input** | `uid = new Uid()`. `out = new OutputObjectState(uid, TYPE); out.packString("hello")`. Call `write_committed(uid, TYPE, out)`, then `read_committed(uid, TYPE)`. |
| **Expected result** | `write_committed` returns `true`. `read_committed` returns a non-null `InputObjectState` whose unpacked string equals `"hello"`. |

---

### TS-CRUD-02

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-CRUD-02 |
| **Source AC** | spec §9.3 read operation "or absent if not found" |
| **Source tasks** | TASK-SA-06 |
| **Type** | Negative |
| **Description** | `read_committed` returns `null` for a key that was never written |
| **Input** | Cache is empty. Call `read_committed(new Uid(), TYPE)`. |
| **Expected result** | Returns `null`. No exception thrown. |

---

### TS-CRUD-03

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-CRUD-03 |
| **Source AC** | spec §9.3 remove operation "Returns a success indicator if the record existed" |
| **Source tasks** | TASK-SA-06 |
| **Type** | Positive |
| **Description** | `remove_committed` returns `true` and the entry is gone after removal |
| **Input** | Write an entry for `uid`. Call `remove_committed(uid, TYPE)`. |
| **Expected result** | Returns `true`. Subsequent `read_committed(uid, TYPE)` returns `null`. |

---

### TS-CRUD-04

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-CRUD-04 |
| **Source AC** | spec §9.3 remove semantics on absent key |
| **Source tasks** | TASK-SA-06 |
| **Type** | Negative |
| **Description** | `remove_committed` returns `false` for a key that does not exist |
| **Input** | Cache is empty. Call `remove_committed(new Uid(), TYPE)`. |
| **Expected result** | Returns `false`. No exception thrown. |

---

### TS-CRUD-05

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-CRUD-05 |
| **Source AC** | spec §9.3 exists operation; `currentState` mapped to `OS_COMMITTED` |
| **Source tasks** | TASK-SA-06 |
| **Type** | Positive |
| **Description** | `currentState` returns `OS_COMMITTED` for a written entry |
| **Input** | Write an entry for `uid`. Call `currentState(uid, TYPE)`. |
| **Expected result** | Returns `StateStatus.OS_COMMITTED`. |

---

### TS-CRUD-06

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-CRUD-06 |
| **Source AC** | spec §9.3 exists operation; `currentState` mapped to `OS_UNKNOWN` |
| **Source tasks** | TASK-SA-06 |
| **Type** | Negative |
| **Description** | `currentState` returns `OS_UNKNOWN` for an absent entry |
| **Input** | Cache is empty. Call `currentState(new Uid(), TYPE)`. |
| **Expected result** | Returns `StateStatus.OS_UNKNOWN`. |

---

### TS-CRUD-07

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-CRUD-07 |
| **Source AC** | spec §9.3 write semantics; null cache guard |
| **Source tasks** | TASK-SA-04, TASK-SA-06 |
| **Type** | Negative |
| **Description** | All write operations throw `ObjectStoreException` when the cache is not initialised |
| **Input** | `InfinispanObjectStore.setCache(null)`. Construct a new `InfinispanObjectStore`. Call `write_committed(new Uid(), TYPE, new OutputObjectState())`. |
| **Expected result** | Throws `ObjectStoreException`. Does not throw `NullPointerException`. |

---

## Section 4 — Enumeration and Key Filtering (`TS-ENUM`)

### TS-ENUM-01

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-ENUM-01 |
| **Source AC** | C-12 "state store enumeration returns all LRA identifiers including those created by other nodes"; spec §9.3 enumerate |
| **Source tasks** | TASK-SA-03, TASK-SA-06 |
| **Type** | Positive |
| **Description** | `allObjUids` returns exactly the UIDs written under the queried type |
| **Input** | Write entries for `uid1` and `uid2` under `TYPE`. Write `uid3` under `OTHER_TYPE`. Call `allObjUids(TYPE, found)`. |
| **Expected result** | Unpacked UIDs from `found` contain `uid1` and `uid2`, but not `uid3`. |

---

### TS-ENUM-02

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-ENUM-02 |
| **Source AC** | C-12 enumeration excludes lock keys; spec §9.2 lock key namespace |
| **Source tasks** | TASK-SA-03, TASK-SA-06 |
| **Type** | Negative |
| **Description** | `allObjUids` excludes `lock/` prefixed entries from results |
| **Input** | Write one LRA entry for `uid` under `TYPE`. Call `tryLock(uid)` to insert a `lock/{uid}` entry. Call `allObjUids(TYPE, found)`. |
| **Expected result** | Unpacked UIDs from `found` contain `uid` only. No string starting with `"lock/"` appears in any unpacked value. |

---

### TS-ENUM-03

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-ENUM-03 |
| **Source AC** | spec §13.4 "failed-LRA area MUST have a distinct key prefix; recovery scans do not re-process them"; C-11 |
| **Source tasks** | TASK-SA-03 |
| **Type** | Negative |
| **Description** | `allObjUids` for the active type does not return `failed/` prefixed entries |
| **Input** | Write one active LRA entry for `uid1` under `TYPE`. Directly insert a key `"failed/{uid2}"` into the cache with dummy bytes. Call `allObjUids(TYPE, found)`. |
| **Expected result** | Unpacked UIDs from `found` contain `uid1` only. `uid2` does not appear. |

---

### TS-ENUM-04

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-ENUM-04 |
| **Source AC** | spec §9.3 enumerate; correctness with empty store |
| **Source tasks** | TASK-SA-03, TASK-SA-06 |
| **Type** | Boundary |
| **Description** | `allObjUids` on an empty cache returns an empty result (null-UID terminator only) |
| **Input** | Cache is empty. Call `allObjUids(TYPE, found)`. |
| **Expected result** | Returns `true`. The first unpacked UID from `found` is `Uid.nullUid()`. |

---

### TS-ENUM-05

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-ENUM-05 |
| **Source AC** | spec §9.3 enumerate; C-12 "all nodes" (cross-node semantics via snapshot) |
| **Source tasks** | TASK-SA-03 |
| **Type** | Positive |
| **Description** | `allTypes` returns the type of a written entry and excludes `lock/` and `failed/` as type names |
| **Input** | Write one entry for `uid` under `TYPE`. Call `tryLock(uid)`. Directly insert `"failed/{uid}"`. Call `allTypes(foundTypes)`. |
| **Expected result** | Unpacked types contain `TYPE`. No type value starts with `"lock"` or `"failed"`. |

---

## Section 5 — Store-Unavailability Exception Mapping (`TS-AVAIL`)

### TS-AVAIL-01

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-AVAIL-01 |
| **Source AC** | spec §9.4 "minority partition MUST refuse all write operations … return HTTP 503"; AC-9.3; TASK-SA-04 |
| **Source tasks** | TASK-SA-04, TASK-SA-06 |
| **Type** | Negative |
| **Description** | `write_committed` on a cache that throws `CacheException` raises `StoreUnavailableException` |
| **Input** | Inject a `FailingCache` that throws `CacheException` on `put`. Call `write_committed(uid, TYPE, state)`. |
| **Expected result** | Throws `StoreUnavailableException` (a subtype of `ObjectStoreException`). Does not throw a raw `CacheException` or `ObjectStoreException` directly. |

---

### TS-AVAIL-02

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-AVAIL-02 |
| **Source AC** | spec §9.4 store unavailability; TASK-SA-04 completion criterion (AvailabilityException path) |
| **Source tasks** | TASK-SA-04 |
| **Type** | Negative |
| **Description** | `write_committed` on a cache that throws `AvailabilityException` (minority partition) raises `StoreUnavailableException` |
| **Input** | Inject a cache stub that throws `org.infinispan.partitionhandling.AvailabilityException` on `put`. Call `write_committed(uid, TYPE, state)`. |
| **Expected result** | Throws `StoreUnavailableException`. `StoreUnavailableException` is a subtype of `ObjectStoreException`. |

---

### TS-AVAIL-03

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-AVAIL-03 |
| **Source AC** | TASK-SA-04 completion criterion "IllegalArgumentException → plain ObjectStoreException" |
| **Source tasks** | TASK-SA-04 |
| **Type** | Negative |
| **Description** | A non-CacheException from the cache does NOT produce `StoreUnavailableException` |
| **Input** | Inject a cache stub that throws `IllegalArgumentException` on `put`. Call `write_committed(uid, TYPE, state)`. |
| **Expected result** | Throws `ObjectStoreException` that is **not** an instance of `StoreUnavailableException`. |

---

### TS-AVAIL-04

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-AVAIL-04 |
| **Source AC** | spec §9.4; spec §15.4 store unavailability on read path |
| **Source tasks** | TASK-SA-04 |
| **Type** | Negative |
| **Description** | `read_committed` on a cache that throws `CacheException` raises `StoreUnavailableException` |
| **Input** | Inject a `FailingCache` that throws `CacheException` on `get`. Call `read_committed(uid, TYPE)`. |
| **Expected result** | Throws `StoreUnavailableException`. |

---

### TS-AVAIL-05

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-AVAIL-05 |
| **Source AC** | spec §9.4; lock operations on unavailable store |
| **Source tasks** | TASK-SA-04 |
| **Type** | Negative |
| **Description** | `tryLock` on an unavailable cache raises `StoreUnavailableException` |
| **Input** | Inject a `FailingCache` that throws `CacheException` on `putIfAbsent`. Call `tryLock(uid)`. |
| **Expected result** | Throws `StoreUnavailableException`. Does not return `false` (which would silently swallow the error). |

---

### TS-AVAIL-06

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-AVAIL-06 |
| **Source AC** | spec §9.4; remove path on unavailable store |
| **Source tasks** | TASK-SA-04 |
| **Type** | Negative |
| **Description** | `remove_committed` on a cache that throws `CacheException` raises `StoreUnavailableException` |
| **Input** | Inject a `FailingCache` that throws `CacheException` on `remove`. Call `remove_committed(uid, TYPE)`. |
| **Expected result** | Throws `StoreUnavailableException`. |

---

### TS-AVAIL-07

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-AVAIL-07 |
| **Source AC** | spec §9.4; `allObjUids` on unavailable store |
| **Source tasks** | TASK-SA-04 |
| **Type** | Negative |
| **Description** | `allObjUids` on a cache that throws `CacheException` raises `StoreUnavailableException` |
| **Input** | Inject a `FailingCache` that throws `CacheException` on `keySet`. Call `allObjUids(TYPE, new InputObjectState())`. |
| **Expected result** | Throws `StoreUnavailableException`. |

---

## Section 6 — Cache Configuration (`TS-CFG`)

### TS-CFG-01

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-CFG-01 |
| **Source AC** | spec §9.4 "Reads used to drive LRA state transitions MUST return the latest committed state"; design §2.1 `REPL_SYNC` |
| **Source tasks** | TASK-SA-05 |
| **Type** | Positive |
| **Description** | `lra-objectstore` cache is configured with `REPL_SYNC` mode |
| **Input** | Initialise `InfinispanConfiguration` in embedded mode. Retrieve the `lra-objectstore` cache configuration. |
| **Expected result** | `config.clustering().cacheMode()` equals `CacheMode.REPL_SYNC`. |

---

### TS-CFG-02

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-CFG-02 |
| **Source AC** | spec §9.4 "minority partition MUST refuse all write operations"; C-08 |
| **Source tasks** | TASK-SA-05 |
| **Type** | Positive |
| **Description** | `lra-objectstore` cache is configured with `DENY_READ_WRITES` partition handling |
| **Input** | Retrieve the `lra-objectstore` cache configuration from `InfinispanConfiguration`. |
| **Expected result** | `config.clustering().partitionHandling().whenSplit()` equals `PartitionHandling.DENY_READ_WRITES`. |

---

### TS-CFG-03

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-CFG-03 |
| **Source AC** | spec §15.3 "reconciliation MUST prioritise the majority-partition state"; design §2.1 `PREFERRED_ALWAYS` |
| **Source tasks** | TASK-SA-05 |
| **Type** | Positive |
| **Description** | `lra-objectstore` cache is configured with `PREFERRED_ALWAYS` merge policy |
| **Input** | Retrieve the `lra-objectstore` cache configuration from `InfinispanConfiguration`. |
| **Expected result** | `config.clustering().partitionHandling().mergePolicy()` equals `MergePolicy.PREFERRED_ALWAYS` or its Infinispan equivalent. |

---

### TS-CFG-04

| Field | Value |
|-------|-------|
| **Scenario ID** | TS-CFG-04 |
| **Source AC** | spec §9.5 "MUST be persistent across a full coordinator cluster restart"; C-13 |
| **Source tasks** | TASK-SA-05 |
| **Type** | Positive |
| **Description** | Global state persistence is enabled in embedded mode |
| **Input** | Initialise `InfinispanConfiguration` in embedded mode. Retrieve global configuration. |
| **Expected result** | `globalConfig.globalState().enabled()` returns `true`. The persistent location is a non-empty path. |

---

## Summary Table

| ID | Area | Type | Source AC / C-xx |
|----|------|------|------------------|
| TS-LOCK-01 | Locking | P | AC-9.4, §14.2 |
| TS-LOCK-02 | Locking | N | AC-9.4, §14.2 |
| TS-LOCK-03 | Locking | P | AC-9.5, §14.2 |
| TS-LOCK-04 | Locking | N | §14.2 conditional remove |
| TS-LOCK-05 | Locking / Config | B | §14.2 default TTL, §16 |
| TS-LOCK-06 | Locking / Config | P | §14.2 configurable, §16 |
| TS-LOCK-07 | Locking / Config | B | §14.2 minimum TTL |
| TS-LOCK-08 | Locking / Config | B | §14.2 minimum TTL (at floor) |
| TS-LOCK-09 | Locking / Config | N | §14.2 robustness |
| TS-LOCK-10 | Locking | P | AC-9.4, §9.2 |
| TS-NODEID-01 | Node Identity | P | C-01, §8.1 |
| TS-NODEID-02 | Node Identity | P | C-01, §8.1 |
| TS-NODEID-03 | Node Identity | P | §8.1 stability |
| TS-CRUD-01 | CRUD | P | §9.3, AC-9.1 |
| TS-CRUD-02 | CRUD | N | §9.3 read absent |
| TS-CRUD-03 | CRUD | P | §9.3 remove |
| TS-CRUD-04 | CRUD | N | §9.3 remove absent |
| TS-CRUD-05 | CRUD | P | §9.3 exists |
| TS-CRUD-06 | CRUD | N | §9.3 exists absent |
| TS-CRUD-07 | CRUD | N | §9.3, null-cache guard |
| TS-ENUM-01 | Enumeration | P | C-12, §9.3 |
| TS-ENUM-02 | Enumeration | N | C-12, §9.2 lock namespace |
| TS-ENUM-03 | Enumeration | N | C-11, §13.4 failed area |
| TS-ENUM-04 | Enumeration | B | §9.3 empty store |
| TS-ENUM-05 | Enumeration | P | §9.3 allTypes |
| TS-AVAIL-01 | Availability | N | AC-9.3, §9.4, §15.4 |
| TS-AVAIL-02 | Availability | N | AC-9.3, §9.4 minority |
| TS-AVAIL-03 | Availability | N | TASK-SA-04 |
| TS-AVAIL-04 | Availability | N | §9.4, §15.4 read path |
| TS-AVAIL-05 | Availability | N | §9.4, §14.2 lock path |
| TS-AVAIL-06 | Availability | N | §9.4 remove path |
| TS-AVAIL-07 | Availability | N | §9.4 enumerate path |
| TS-CFG-01 | Configuration | P | §9.4 REPL_SYNC |
| TS-CFG-02 | Configuration | P | C-08, §9.4 partition |
| TS-CFG-03 | Configuration | P | §15.3 merge policy |
| TS-CFG-04 | Configuration | P | C-13, §9.5 persistence |

**Total: 36 scenarios** — 19 positive, 14 negative, 4 boundary.

---

## Notes for Implementors

1. **`FailingCache` test double.** Scenarios in `TS-AVAIL` require injecting a cache whose targeted operations throw `CacheException`. The existing `FailingCache` static inner class in `InfinispanObjectStoreTest` already satisfies this; route individual scenarios to its specific throwing methods.

2. **Embedded cache for CRUD and ENUM tests.** Use `DefaultCacheManager` with `nonClusteredDefault()` and a local cache configuration — no JGroups, no network, no external process.

3. **`TS-CFG` tests require `InfinispanConfiguration`.** These cannot use the embedded-only `InfinispanObjectStoreTest` setup; they need to exercise `InfinispanConfiguration.configureObjectStore()` in a controlled embedded path.

4. **`TS-LOCK-10` correlation.** After `tryLock`, read the raw cache bytes to verify the node ID value — this is a whitebox check and is intentional. The goal is to confirm the three-step derivation (§8.1) propagates all the way into the stored lock entry.

5. **Property cleanup.** All scenarios that set `System.setProperty(...)` must clear the property in a `finally` block (or `@AfterEach`) to avoid cross-test pollution.

---

*End of unit-test-scenarios.md*
