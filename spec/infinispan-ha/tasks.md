# Distributed LRA Coordinator — Build Tasks
## Slice: State Store Adaptor (`InfinispanObjectStore` + `HAMetadataStore`)

**Version:** 1.0-DRAFT  
**Status:** Draft  
**Plan reference:** [plan.md](plan.md) §3.3, §4.2, §4.3, §4.4, §5  
**Design reference:** [design.md](design.md) §2.1, §2.3, §5.1–§5.3, §6, §7.1–§7.2  
**Spec reference:** [distributed-lra-coordinator-spec.md](distributed-lra-coordinator-spec.md) §9, §14, C-07, C-08, C-12

---

## Why this slice

The State Store Adaptor is the lowest-level component in the system. Every other HA component — `LRAService` cross-node lookup, `LRARecoveryModule` scan loop, `InfinispanConfiguration` startup wiring — depends on `InfinispanObjectStore` and `HAMetadataStore` being correct and complete. It is self-contained: it has no upstream HA dependency and can be verified in isolation with unit tests against an embedded Infinispan instance.

Delivering this slice first means all four overlying components can be built against a validated, stable adaptor contract.

---

## Current baseline

The following already exists on the `lra-infinispan` branch:

| Artefact | State |
|----------|-------|
| `InfinispanObjectStore` — `write_committed`, `read_committed`, `remove_committed`, `allObjUids`, `allTypes`, `currentState` | Implemented |
| `InfinispanObjectStore` — `tryLock` (30 s hard-coded TTL, `putIfAbsent`) | Implemented |
| `InfinispanObjectStore` — `releaseLock` (conditional `cache.remove`) | Implemented |
| `InfinispanObjectStore` — `write_uncommitted` (delegates to `write_committed`) | Implemented |
| `InfinispanObjectStore` — `commit_state` (no-op) | Implemented |
| `HAMetadataStore` interface (`tryLock`, `releaseLock`) | Defined |
| `InfinispanConfiguration` — `lra-objectstore` cache with `REPL_SYNC` + `DENY_READ_WRITES` + `PREFERRED_ALWAYS` | Implemented |
| `InfinispanConfiguration` — global state persistence | Implemented |

The following is **not yet done** or **not yet correct**:

| Gap | Source |
|-----|--------|
| `tryLock` TTL is hard-coded to `30`. Must read `lra.coordinator.lock.ttl.seconds` with a minimum of 10 s | plan §5.2, spec §14.2, spec §16 |
| `tryLock` node ID falls back to `"unknown"` when `lra.coordinator.node.id` is not set. Must use the same derivation as `InfinispanConfiguration.getNodeName()` (hostname, then generated) | plan §3.3, spec §9.2 (lock value = node ID) |
| `allObjUids` skips keys containing a secondary `/` in the UID segment. The current check (`uidStr.contains("/")`) discards valid UIDs whose format includes a `/`; must exclude only `lock/` and `failed/` prefixes, not UID content | plan §5.1, spec §9.3 (enumerate), C-12 |
| `allObjUids` does not filter out `failed/` prefixed keys from the active scan | plan §5.3, spec §13.4 |
| `write_committed` does not distinguish store unavailability (Infinispan `AvailabilityException` / `CacheException`) from other exceptions. The plan requires this to surface as a distinct type to callers | plan §3.3 ("Surface store unavailability … as a distinct exception type that maps to HTTP 503") |
| No unit tests exist for `InfinispanObjectStore` in the `coordinator-ha-infinispan` test directory | plan §4.3 ("implemented and tested") |

---

## Tasks

---

### TASK-SA-01 — Read lock TTL from configuration property

**Task ID:** TASK-SA-01  
**Title:** Replace hard-coded 30 s lock TTL with `lra.coordinator.lock.ttl.seconds` system property

**Source reference:**  
- plan.md §3.3: "Implement `HAMetadataStore.tryLock` using `cache.putIfAbsent(lockKey, nodeId, ttl, SECONDS)`"  
- plan.md §5.2: "TTL — Configurable. Default 30 s. Minimum 10 s. (`lra.coordinator.lock.ttl.seconds`)"  
- spec §14.2: "MUST be configurable … MUST be ≥ 10 seconds … Default MUST be 30 seconds"  
- spec §16: property `lra.coordinator.lock.ttl.seconds`

**Implementation notes:**

In [`InfinispanObjectStore.tryLock`](../coordinator-ha-infinispan/src/main/java/io/narayana/lra/coordinator/infinispan/InfinispanObjectStore.java), replace the literal `30` with a helper that reads the system property:

```java
private static long getLockTtlSeconds() {
    long ttl = Long.getLong("lra.coordinator.lock.ttl.seconds", 30L);
    return Math.max(ttl, 10L);
}
```

Call `getLockTtlSeconds()` instead of the literal `30` in the `putIfAbsent` invocation.

The minimum enforcement (`Math.max(..., 10)`) satisfies the spec's "MUST be ≥ 10 seconds" requirement and prevents a misconfigured value from creating degenerate lock behaviour.

**Expected output:**  
`tryLock` passes `getLockTtlSeconds()` as the TTL argument in all invocations.

**Completion criteria:**
- `lra.coordinator.lock.ttl.seconds=60` → `putIfAbsent` called with TTL 60 s.
- `lra.coordinator.lock.ttl.seconds=5` → `putIfAbsent` called with TTL 10 s (minimum enforced).
- No system property set → `putIfAbsent` called with TTL 30 s.
- Unit test (TASK-SA-06) covers all three cases.

**Dependencies:** None.

---

### TASK-SA-02 — Use stable node ID in lock entries

**Task ID:** TASK-SA-02  
**Title:** Derive node ID for lock value consistently with `InfinispanConfiguration.getNodeName()`

**Source reference:**  
- plan.md §3.3: lock value must be "this node's node ID"  
- spec §9.2: "Value: the node ID of the lock holder (UTF-8 string)"  
- spec §8.1: "Node ID MUST be stable across restarts of the same logical node"  
- spec §16: property `lra.coordinator.node.id`

**Implementation notes:**

The current `getNodeId()` in `InfinispanObjectStore` reads only `lra.coordinator.node.id` and falls back to `"unknown"`. This differs from `InfinispanConfiguration.getNodeName()`, which tries `lra.coordinator.node.id`, then `HOSTNAME` env variable, then generates a value. If `lra.coordinator.node.id` is not set, `tryLock` stores `"unknown"` as the lock owner, making `releaseLock`'s conditional remove unreliable when multiple nodes are involved.

Extract the node ID derivation into a shared static utility, or duplicate the three-step logic directly in `InfinispanObjectStore.getNodeId()`:

```java
private static String getNodeId() {
    String id = System.getProperty("lra.coordinator.node.id");
    if (id == null || id.isEmpty()) {
        id = System.getenv("HOSTNAME");
    }
    if (id == null || id.isEmpty()) {
        // Deterministic within a JVM instance; sufficient for lock ownership
        id = "lra-coord-" + ProcessHandle.current().pid();
    }
    return id;
}
```

The value must be consistent across calls within the same JVM process so that `tryLock` and `releaseLock` use the same byte array.

**Expected output:**  
`getNodeId()` returns the same non-`"unknown"` value in all environments. Lock entries are owned by a stable, non-generic identifier.

**Completion criteria:**
- With `lra.coordinator.node.id=node-A`: lock value bytes decode to `"node-A"`.
- With no property but `HOSTNAME=pod-1`: lock value bytes decode to `"pod-1"`.
- `tryLock` followed immediately by `releaseLock` on the same JVM removes the lock entry (confirmed by `cache.containsKey`).
- Unit test (TASK-SA-06) covers the `releaseLock` idempotency case.

**Dependencies:** None.

---

### TASK-SA-03 — Fix `allObjUids` to exclude `lock/` and `failed/` keys correctly

**Task ID:** TASK-SA-03  
**Title:** Correct active-LRA enumeration to exclude lock entries and failed-LRA records without discarding valid UIDs

**Source reference:**  
- plan.md §3.3: "Enumerate all LRA UIDs across all nodes (not only local — spec C-12)"  
- plan.md §5.3: failed-LRA key pattern is `failed/{objectStoreType}/{uid}`  
- spec §9.3 (enumerate): "Return the set of all LRA identifiers stored under the given type prefix, including those written by other nodes"  
- spec §13.4: "failed-LRA area MUST have a distinct key prefix … recovery scans do not re-process them"  
- spec C-12: enumeration must include records from all nodes

**Implementation notes:**

There are two bugs in the current [`allObjUids`](../coordinator-ha-infinispan/src/main/java/io/narayana/lra/coordinator/infinispan/InfinispanObjectStore.java:142):

1. **Line 151-153**: `if (uidStr.contains("/")) { continue; }` — this skips any UID whose `fileStringForm()` representation contains a `/`. Narayana Uid string forms do not contain `/`, but the check is logically wrong for future safety and hides the real intent. The actual goal is to skip secondary-level keys. Replace with an explicit prefix exclusion.

2. **Missing**: keys with the `failed/` prefix are stored in the same cache. A `keySet()` scan over `type + "/"` will never match a key that starts with `failed/` because the type prefix is different — but the enumeration must also be explicitly correct when called with the failed-LRA type path. No change is needed to the filter itself, but the skip list should be documented.

Rewrite the body of `allObjUids` to make the exclusions explicit and remove the ambiguous `contains("/")` guard:

```java
for (String k : cache().keySet()) {
    if (!k.startsWith(prefix)) {
        continue;
    }
    // Exclude distributed lock entries and failed-LRA records
    // (these live under "lock/" and "failed/" prefixes respectively,
    // which never match a type prefix that starts with "/StateManager/")
    String uidStr = k.substring(prefix.length());
    // uidStr is the Uid fileStringForm — it does not contain "/"
    UidHelper.packInto(new Uid(uidStr), buffer);
}
```

Remove the `uidStr.contains("/")` guard. The `prefix` already scopes the iteration to the correct type path.

**Expected output:**  
`allObjUids` returns every active LRA UID under the given type prefix and no lock or failed-LRA entries.

**Completion criteria:**
- Cache containing three active LRA entries, two lock entries, and one failed-LRA entry → `allObjUids` returns exactly three UIDs.
- Cache containing an LRA whose UID `fileStringForm()` contains no `/` is returned. (Existing behaviour confirmed by test.)
- `allObjUids` called with the failed-LRA type path returns failed-LRA UIDs only.
- Unit test (TASK-SA-06) covers mixed-key scenario.

**Dependencies:** None.

---

### TASK-SA-04 — Surface store unavailability as a distinct exception type

**Task ID:** TASK-SA-04  
**Title:** Wrap Infinispan `AvailabilityException` and `CacheException` as a distinct store-unavailable exception so callers can map it to HTTP 503

**Source reference:**  
- plan.md §3.3: "Surface store unavailability to the Coordinator Service as a distinct exception type that maps to HTTP 503"  
- spec §9.4: "minority partition MUST refuse all write operations … return HTTP 503"  
- spec §15.4: "coordinator nodes MUST stop processing write operations and return HTTP 503"  
- architecture §4.3: "Surface store unavailability to the Coordinator Service as a distinct error"

**Implementation notes:**

Currently all `catch (Exception e)` blocks in `InfinispanObjectStore` wrap every exception as a generic `ObjectStoreException`. This means `LRAService` cannot distinguish a transient store-unavailable condition (which should yield HTTP 503) from a programming error or serialisation failure (which should yield HTTP 500).

Infinispan raises `org.infinispan.commons.CacheException` for general cache errors and `org.infinispan.partitionhandling.AvailabilityException` (a subclass of `CacheException`) when `DENY_READ_WRITES` blocks a write on a minority partition.

**Step 1 — define a marker exception** (or reuse an existing one in Narayana):

Check whether `com.arjuna.ats.arjuna.exceptions.ObjectStoreException` already has a subclass for availability. If not, add a minimal subclass in `coordinator/src/main/java/io/narayana/lra/coordinator/internal/`:

```java
// StoreUnavailableException.java — in coordinator module, zero dependency on Infinispan
public class StoreUnavailableException extends ObjectStoreException {
    public StoreUnavailableException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }
}
```

**Step 2 — update `InfinispanObjectStore`** to catch `AvailabilityException` first (before the generic `Exception` catch) and rethrow as `StoreUnavailableException`:

```java
} catch (org.infinispan.partitionhandling.AvailabilityException ae) {
    throw new StoreUnavailableException("Store unavailable (minority partition)", ae);
} catch (org.infinispan.commons.CacheException ce) {
    // CacheException covers connectivity loss, timeout, and node departure
    throw new StoreUnavailableException("Store unavailable: " + ce.getMessage(), ce);
} catch (Exception e) {
    throw new ObjectStoreException(e);
}
```

Apply this pattern to `write_committed`, `read_committed`, `remove_committed`, `tryLock`, and `releaseLock`. Read-only operations (`currentState`, `allObjUids`, `allTypes`) may also be wrapped where appropriate, but write operations are the priority because they are on the HTTP request path.

**Step 3** — document in `HAMetadataStore` that `tryLock` may throw `StoreUnavailableException` so `LRAService` knows to catch it.

**Expected output:**  
Any write to a minority-partition `InfinispanObjectStore` throws `StoreUnavailableException` rather than a generic `ObjectStoreException`. `LRAService` can catch `StoreUnavailableException` specifically and return HTTP 503; other `ObjectStoreException` subtypes map to HTTP 500.

**Completion criteria:**
- A mock Infinispan cache that throws `AvailabilityException` on `put` → `write_committed` throws `StoreUnavailableException`.
- A mock cache that throws a generic `CacheException` → `write_committed` throws `StoreUnavailableException`.
- A mock cache that throws `IllegalArgumentException` → `write_committed` throws plain `ObjectStoreException` (not `StoreUnavailableException`).
- `StoreUnavailableException` is in the `coordinator` module; `InfinispanObjectStore` in `coordinator-ha-infinispan` catches the Infinispan type and converts.
- Unit test (TASK-SA-06) covers all three scenarios.

**Dependencies:** None. (Callers in `LRAService` that act on `StoreUnavailableException` are in the Coordinator Service slice, not this slice.)

---

### TASK-SA-05 — Verify `InfinispanConfiguration` uses `PREFERRED_ALWAYS` merge policy for persistence config

**Task ID:** TASK-SA-05  
**Title:** Confirm and harden the `lra-objectstore` cache definition: REPL_SYNC + DENY_READ_WRITES + PREFERRED_ALWAYS + global state persistence

**Source reference:**  
- plan.md §3.5: "Define the `lra-objectstore` cache with `REPL_SYNC`, `DENY_READ_WRITES`, and `PREFERRED_ALWAYS` merge policy"  
- plan.md §3.5: "Enable Infinispan global state persistence to `lra.coordinator.infinispan.persistent.location`"  
- design.md §2.1: all four cache properties defined  
- spec §9.4: linearisable writes + minority partition refusal  
- spec §9.5: persistent across full cluster restart

**Implementation notes:**

`InfinispanConfiguration.configureObjectStore()` already sets `REPL_SYNC`, `DENY_READ_WRITES`, and `PREFERRED_ALWAYS`. `initializeEmbedded()` already calls `globalConfig.globalState().enable().persistentLocation(...)`.

However, two gaps exist:

1. **`initializeEmbedded()` defines `lra-active` cache** (line 158), not `lra-objectstore`. The `lra-objectstore` cache is only defined in `configureObjectStore()`. If `configureObjectStore()` is called before `initializeEmbedded()` finishes or on a WildFly-managed manager that already has an `lra-objectstore` cache with different settings, the merge policy may not be applied. Add a defensive log at INFO level confirming the active configuration after `getCache(cacheName)` is called:

```java
org.infinispan.configuration.cache.Configuration actual = cacheManager.getCacheConfiguration(cacheName);
LRALogger.logger.infof(
    "lra-objectstore cache config: mode=%s, partitionHandling=%s, mergePolicy=%s",
    actual.clustering().cacheMode(),
    actual.clustering().partitionHandling().whenSplit(),
    actual.clustering().partitionHandling().mergePolicy());
```

2. **`initializeEmbedded()` sets `globalState().enable()`** but only in the embedded path. The WildFly JNDI path leaves persistence managed by the subsystem. Document this explicitly in the method Javadoc so it is not treated as a gap.

3. **Remove the orphaned `lra-active` cache definition** from `initializeEmbedded()`. The plan uses only `lra-objectstore`. The `lra-active` cache was from an older design. If it is not used by any code path, its definition creates dead configuration that may confuse operators.

**Expected output:**  
`lra-objectstore` cache is confirmed at startup as `REPL_SYNC` + `DENY_READ_WRITES` + `PREFERRED_ALWAYS` in the embedded path. Dead `lra-active` cache definition is removed or replaced with a comment explaining why it no longer exists.

**Completion criteria:**
- INFO log confirms cache mode and partition handling at startup.
- `initializeEmbedded()` no longer defines `lra-active` (or documents the reason if it is intentionally kept).
- No existing test broken by the removal.
- Unit test (TASK-SA-06) covers the cache config verification path.

**Dependencies:** None.

---

### TASK-SA-06 — Write unit tests for `InfinispanObjectStore`

**Task ID:** TASK-SA-06  
**Title:** Add unit tests for all `InfinispanObjectStore` operations using an embedded in-process Infinispan cache

**Source reference:**  
- plan.md §4.3: "The following `ObjectStoreAPI` operations must be implemented and tested"  
- plan.md §3.3 (must-list): all six operations + `tryLock` + `releaseLock`  
- spec §9.3: all seven required operations  
- spec §14.2: lock acquire + TTL + conditional release

**Implementation notes:**

There are no unit tests for `InfinispanObjectStore` in the existing test directory (`coordinator-ha-infinispan/src/test/.../InfinispanClusterCoordinatorTest.java` and `InfinispanClusterFailoverTest.java` both test `InfinispanClusterCoordinator`, not the adaptor).

Create `InfinispanObjectStoreTest.java` in the same package. Use an embedded `DefaultCacheManager` with a local (non-clustered) `LOCAL` cache to keep tests fast and dependency-free. Each test method sets up the cache via `InfinispanObjectStore.setCache(cache)` and tears it down in `@AfterEach`.

**Test cases to cover:**

| Test | Verifies |
|------|----------|
| `write_then_read_returns_same_bytes` | `write_committed` + `read_committed` round-trip |
| `read_absent_returns_null` | `read_committed` on missing key returns `null` |
| `write_then_remove_returns_absent` | `remove_committed` removes the entry; `currentState` returns `OS_UNKNOWN` |
| `remove_absent_returns_false` | `remove_committed` on missing key returns `false` |
| `allObjUids_returns_only_active_lras` | Mix of active, lock, and failed-LRA keys; only active returned |
| `allTypes_excludes_lock_prefix` | `allTypes` does not include `lock` as a type |
| `currentState_committed_and_unknown` | `currentState` returns `OS_COMMITTED` when present, `OS_UNKNOWN` when absent |
| `tryLock_returns_true_on_first_acquire` | `putIfAbsent` semantics |
| `tryLock_returns_false_when_already_locked` | Second `tryLock` on the same UID returns `false` |
| `releaseLock_removes_lock_entry` | After `tryLock` + `releaseLock`, `containsKey("lock/…")` is `false` |
| `releaseLock_noop_if_not_owner` | `releaseLock` with a different node ID does not remove the lock |
| `tryLock_ttl_read_from_property` | System property `lra.coordinator.lock.ttl.seconds=60` results in TTL 60 s |
| `tryLock_ttl_minimum_enforced` | Property value `5` results in TTL 10 s |
| `write_cache_null_throws_ObjectStoreException` | `cache()` guard when store not initialised |
| `write_availability_exception_throws_StoreUnavailableException` | Mock cache throws `AvailabilityException`; `write_committed` throws `StoreUnavailableException` |
| `write_cache_exception_throws_StoreUnavailableException` | Mock cache throws `CacheException`; `write_committed` throws `StoreUnavailableException` |

Use Mockito (already in the project's test scope) for the exception scenarios. Use a real embedded cache for all other cases.

**Expected output:**  
`InfinispanObjectStoreTest` with the 16 test methods above, all green, with no flakiness on repeated runs.

**Completion criteria:**
- All 16 test methods pass with `mvn test -pl coordinator-ha-infinispan`.
- No test requires network access or an external Infinispan process.
- Tests complete in under 10 seconds total.
- Code coverage on `InfinispanObjectStore` is ≥ 85 % line coverage (checked by Jacoco if configured).

**Dependencies:** TASK-SA-01, TASK-SA-02, TASK-SA-03, TASK-SA-04, TASK-SA-05 (all fixes should be in place before test suite is finalised, though tests can be written incrementally alongside the fixes).

---

## Completion checklist for this slice

All tasks in this slice are complete when:

- [ ] `tryLock` reads TTL from `lra.coordinator.lock.ttl.seconds` with 10 s minimum (TASK-SA-01)
- [ ] `getNodeId()` uses the three-step derivation; no fallback to `"unknown"` (TASK-SA-02)
- [ ] `allObjUids` removes the `contains("/")` guard; correctly excludes only `lock/` and `failed/` prefixed keys (TASK-SA-03)
- [ ] `write_committed`, `read_committed`, `remove_committed`, `tryLock`, `releaseLock` throw `StoreUnavailableException` on Infinispan availability/cache errors (TASK-SA-04)
- [ ] `lra-objectstore` cache definition log confirmed at startup; dead `lra-active` definition removed (TASK-SA-05)
- [ ] 16 unit tests pass; no external dependencies; runs in < 10 s (TASK-SA-06)
- [ ] `mvn verify -pl coordinator-ha-infinispan` passes with no new warnings
- [ ] `mvn verify -pl coordinator` passes (no regressions in the core module from `StoreUnavailableException` addition)

---

*End of Task File*

---

*Derived from approved design artifacts. Tasks scoped to the State Store Adaptor slice only. No tasks outside the approved plan are included.*
