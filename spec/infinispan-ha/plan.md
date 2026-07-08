# Distributed MicroProfile LRA Coordinator — Implementation Plan

**Version:** 1.0-DRAFT  
**Status:** Draft  
**Spec reference:** [distributed-lra-coordinator-spec.md](distributed-lra-coordinator-spec.md) (LRA-DIST-COORD-001)  
**Architecture reference:** [architecture.md](architecture.md)  
**Design reference:** [design.md](design.md)

---

## Table of Contents

1. [Summary](#1-summary)
2. [Technical Context](#2-technical-context)
3. [Components and Boundaries](#3-components-and-boundaries)
4. [Interfaces and Integrations](#4-interfaces-and-integrations)
5. [Data Model Summary](#5-data-model-summary)
6. [Key Decisions and Rationale](#6-key-decisions-and-rationale)
7. [Risks and Assumptions](#7-risks-and-assumptions)

---

## 1. Summary

This plan describes what must be built to deliver a distributed, HA-capable MicroProfile LRA coordinator that eliminates the current single-point-of-failure constraint (spec G-01). The target state is a cluster of three or more coordinator nodes, each running an embedded Infinispan cache, connected via JGroups, where any node can handle any LRA request and the system tolerates a minority node failure without data loss or service interruption.

### What changes from today's single-node coordinator

| Area | Today (single-node) | Target (HA cluster) |
|------|---------------------|---------------------|
| State persistence | Local filesystem ObjectStore | Infinispan `lra-objectstore` (REPL_SYNC), replicated to all nodes |
| Coordinator availability | Single process; crash = outage | 3+ nodes; minority crash = no outage |
| Recovery authority | Any restart drives recovery | Elected recovery leader only |
| LRA routing | Always same node | Any node via cross-node Infinispan read |
| Distributed locking | Not needed (single node) | `putIfAbsent` / conditional remove in Infinispan, with TTL |
| Cluster membership | Not applicable | JGroups view-based membership and failure detection |

### What does not change

- The MicroProfile LRA REST API surface. All endpoints are identical (spec §17.2, C-10).
- The LRA state machine. State transitions follow the spec unchanged (spec Appendix A.1).
- Participant callback semantics. Complete and compensate URLs are called in the same way (spec §17.2).
- Single-node mode. When `lra.coordinator.ha.enabled=false`, the coordinator behaves exactly as today (spec G-06, C-09, NFR-04).
- The Narayana ObjectStore abstraction boundary. HA mode replaces the backend only; the abstraction is unchanged.

### Activation model

HA mode is strictly opt-in and additive (architecture AD-05). The existing `coordinator` module has zero compile-time dependency on `coordinator-ha-infinispan`. HA components initialise only when both the Maven artifact and `lra.coordinator.ha.enabled=true` are present. All other configurations continue to start unchanged.

---

## 2. Technical Context

### 2.1 Existing codebase baseline

The `lra-infinispan` branch of Narayana already contains foundational work that this plan builds on:

| Existing artefact | Role in the plan |
|-------------------|-----------------|
| `coordinator-ha-infinispan` Maven module | Contains all HA-specific classes; stays separate from `coordinator` core |
| `InfinispanObjectStore` | State Store Adaptor — implements `ObjectStoreAPI` + `HAMetadataStore` for Infinispan. Needs to be verified / extended to cover all required operations listed in spec §9.3 |
| `InfinispanClusterCoordinator` | Cluster Membership Service — `@ViewChanged` listener, view-based leader election, `CoordinatorChangeListener` notification. Largely complete. |
| `InfinispanConfiguration` | CDI `@ApplicationScoped` bean — produces `EmbeddedCacheManager`, defines `lra-objectstore` cache, wires `InfinispanObjectStore` into Narayana `StoreManager`. |
| `HAMetadataStore` interface | Extension of `ObjectStoreAPI` exposing `tryLock` / `releaseLock`. Used by `LRAService` for HA-mode locking. |
| `ClusterCoordinationService` interface | Stable notification boundary between the membership service and `LRARecoveryModule`. |
| `LRARecoveryModule` | Existing recovery module; `isRecoveryLeader()` and `isHaEnabled()` hooks already present. Needs to be verified that the full recovery scan loop respects leader status and uses distributed locks. |
| `LRAService` | Core service; cross-node lookup path (`loadFromObjectStore`) already sketched. Needs to be verified that every write path goes through `InfinispanObjectStore.write_committed` before responding. |

### 2.2 Runtime environments

| Environment | Infinispan mode | JGroups discovery |
|-------------|-----------------|-------------------|
| Quarkus (embedded) | Embedded `DefaultCacheManager` | UDP multicast (dev/test), `KUBE_PING` (production on Kubernetes) |
| WildFly / JBoss EAP | WildFly Infinispan subsystem via JNDI (`java:jboss/infinispan/container/lra`) | Managed by WildFly subsystem |
| Kubernetes / OpenShift | Embedded `DefaultCacheManager` | `KUBE_PING` with headless `StatefulSet` service |

The `InfinispanConfiguration` bean already handles both JNDI and embedded initialisation paths.

### 2.3 Constraints

- **MicroProfile LRA TCK must pass in both HA and non-HA mode** (spec C-10). No TCK test may be broken by HA changes.
- **No compile-time dependency from `coordinator` to `coordinator-ha-infinispan`** (architecture AD-05). CDI lookups in the core use null-safe fallbacks.
- **JDK 17+** (spec ASSUME-05).
- **Minimum cluster size: 3 nodes** for HA (spec ASSUME-04, G-05). Two-node clusters are not supported.

---

## 3. Components and Boundaries

Five components make up the complete system. The boundaries below define what each component owns and what it must never do.

### 3.1 REST API Layer

**Owns:** JAX-RS resources (`Coordinator`, `RecoveryCoordinator`). HTTP request parsing and response construction.

**Must:**
- Serve all MicroProfile LRA endpoints on every coordinator node.
- Accept requests for any LRA regardless of which node started it.
- Return `503` when the Coordinator Service signals store unavailability or lock timeout.
- Return `500` when the Coordinator Service signals a store write failure.
- Return `404` when the LRA is not found in the store.

**Must not:**
- Read from or write to Infinispan directly.
- Make any decision about leader status or recovery.

*Spec: §17.2, Appendix A.2 | Architecture: §4.1*

---

### 3.2 Coordinator Service (`LRAService` + `LRARecoveryModule`)

**Owns:** In-memory LRA state map, LRA state machine transitions, participant callback dispatch, recovery scan scheduling.

**Must:**
- Ensure every LRA state write reaches Infinispan (via the adaptor) before returning a success response (write-before-respond, spec §12.1–§12.3).
- If a state store write fails, revert the in-memory state transition and return HTTP 500 to the caller. Must not return success on an unacknowledged write (spec Appendix A.1, cross-cutting §6.1).
- Perform a cross-node Infinispan read when an LRA is not in local memory, and activate it before processing (spec §10.3).
- Acquire a distributed lock before any close, cancel, or recovery write (spec §12.3, §13.2).
- Release the distributed lock (with conditional remove) on **every** exit path of any close, cancel, or recovery operation — success, write failure, and callback failure alike.
- Run recovery scans only when `LRARecoveryModule.isRecoveryLeader()` is true (spec §13.1).
- Trigger an immediate recovery scan on `onBecameCoordinator()` (spec §11.3).
- Stop scheduling recovery scans immediately on `onLostCoordinator()`.
- During each recovery scan cycle, check Active LRAs for elapsed timeouts and transition them to `Cancelling` before driving compensation callbacks (spec Appendix A.1 — timeout transition).
- On close or cancel of a parent LRA, read each child LRA record from the store and drive it to terminal state before finalising the parent (spec §12.5).
- On node startup with HA enabled, read LRA records from the distributed store to rebuild local in-memory state before accepting requests; defer cluster-wide recovery to the elected leader (spec §13.1 — startup trigger).
- Move exhausted LRAs to the `failed/` key area; never delete them (spec §13.4, C-11).

**Must not:**
- Access Infinispan directly. All store interactions go through `InfinispanObjectStore`.
- Implement leader election logic. It receives change notifications via `CoordinatorChangeListener`.
- Send participant callbacks before persisting the corresponding state transition (architecture AD-04).

*Spec: §10.3, §12.1–§12.3, §13 | Architecture: §4.2, AD-04*

---

### 3.3 State Store Adaptor (`InfinispanObjectStore` + `HAMetadataStore`)

**Owns:** All Infinispan cache operations. Distributed lock protocol. Key format translation.

**Must:**
- Implement all six `ObjectStoreAPI` operations required by spec §9.3 (`write`, `read`, `remove`, `enumerate`, `exists`, and the Narayana lifecycle methods).
- Implement `HAMetadataStore.tryLock` using `cache.putIfAbsent(lockKey, nodeId, ttl, SECONDS)`.
- Implement `HAMetadataStore.releaseLock` using `cache.remove(lockKey, nodeId)` (conditional remove — owner check).
- Block on every write until Infinispan REPL_SYNC confirms replication to all nodes (spec NFR-03).
- Surface store unavailability to the Coordinator Service as a distinct exception type that maps to HTTP 503.
- Enumerate all LRA UIDs across all nodes (not only local — spec C-12).

**Must not:**
- Contain any LRA business logic.
- Make HTTP calls to participants.
- Participate in leader election.

*Spec: §9.3, §14.2, C-07, C-12 | Architecture: §4.3, AD-03*

---

### 3.4 Cluster Membership Service (`InfinispanClusterCoordinator`)

**Owns:** JGroups view-change listener, leader status evaluation, `CoordinatorChangeListener` notification.

**Must:**
- Register a `@ViewChanged` listener on the `EmbeddedCacheManager` at initialisation.
- On each view change, compare the local JGroups address to the first member in the new view. If equal, `isCoordinator = true`.
- Notify all registered `CoordinatorChangeListener` instances synchronously on transition from follower→leader or leader→follower.
- Ensure leader status is kept current. MAY supplement event-driven view-change notifications with a periodic self-check at a configurable interval as a safety net against missed events.
- Shut down the internal scheduler and remove the listener cleanly on `shutdown()`.

**Must not:**
- Read or write LRA records.
- Communicate with participants.
- Access Infinispan caches directly (only the `EmbeddedCacheManager` for membership metadata).

*Spec: §8.2, §11.2, §11.3 | Architecture: §4.4, AD-02*

---

### 3.5 `InfinispanConfiguration` (CDI startup wiring)

**Owns:** `EmbeddedCacheManager` lifecycle, `lra-objectstore` cache definition, ObjectStore backend wiring.

**Must:**
- Check `lra.coordinator.ha.enabled` before doing anything. If false, initialise nothing and return immediately (spec NFR-04, C-09).
- Attempt JNDI lookup for a WildFly-managed `EmbeddedCacheManager` first; fall back to creating an embedded `DefaultCacheManager`.
- Define the `lra-objectstore` cache with `REPL_SYNC`, `DENY_READ_WRITES`, and `PREFERRED_ALWAYS` merge policy.
- Enable Infinispan global state persistence to `lra.coordinator.infinispan.persistent.location` (spec §9.5).
- Call `StoreManager.shutdown()` before re-wiring, then register `InfinispanObjectStore` as the new ObjectStore backend.
- Produce `EmbeddedCacheManager` and `ClusterCoordinationService` as CDI `@ApplicationScoped` beans for injection by the Coordinator Service.
- Stop the `EmbeddedCacheManager` (if not container-managed) and null out the cache reference on `@PreDestroy`.

**Must not:**
- Initialise when `lra.coordinator.ha.enabled` is not explicitly `true`.
- Be imported by the `coordinator` core module at compile time.

*Spec: §16, G-06, NFR-04 | Architecture: AD-05, §6.5 | Design: §3*

---

## 4. Interfaces and Integrations

### 4.1 `ClusterCoordinationService` — the membership notification boundary

This interface is the only stable contract between the Cluster Membership Service and the Coordinator Service. It must not be changed without reviewing all existing uses.

```
ClusterCoordinationService
  isCoordinator()              → boolean
  isInitialized()              → boolean
  addCoordinatorChangeListener(listener)
  removeCoordinatorChangeListener(listener)

  CoordinatorChangeListener
    onBecameCoordinator()
    onLostCoordinator()
```

`LRARecoveryModule` registers itself as a `CoordinatorChangeListener` at startup (when HA is enabled). `InfinispanClusterCoordinator` implements `ClusterCoordinationService`. The core `coordinator` module depends only on the interface, not the Infinispan implementation.

*Design: §3 (component map) | Architecture: §4.4*

---

### 4.2 `HAMetadataStore` — the distributed lock boundary

This interface extends `ObjectStoreAPI` with lock operations. `LRAService` casts the configured ObjectStore to `HAMetadataStore` when HA mode is active. The cast is null-safe: if the store does not implement `HAMetadataStore`, HA locking is silently skipped (non-HA path).

```
HAMetadataStore
  tryLock(uid)       → boolean   // CAS putIfAbsent; returns true on acquisition
  releaseLock(uid)   → void      // conditional remove; no-op if not owner
```

Both operations must be callable by `LRAService` without knowing the underlying Infinispan mechanics.

*Design: §3, §2.3 | Architecture: §4.3, AD-03*

---

### 4.3 Narayana `ObjectStoreAPI` + `StoreManager` wiring

`InfinispanObjectStore` implements `ObjectStoreAPI` and is registered as the active ObjectStore backend via `new StoreManager(store, store, store)` at startup in `InfinispanConfiguration`. This replaces the filesystem backend for HA mode. The Narayana transaction engine (LongRunningAction serialisation, `activate()`, `deactivate()`) continues to work unchanged against this backend.

The following `ObjectStoreAPI` operations must be implemented and tested:

| Operation | Used by |
|-----------|---------|
| `write_committed` | Every LRA state write |
| `read_committed` | Cross-node LRA lookup, recovery read |
| `remove_committed` | LRA terminal cleanup, failed-LRA promotion |
| `allObjUids` | Recovery scan enumeration |
| `allTypes` | Recovery module type discovery |
| `currentState` | Existence check |

The Infinispan adaptor implements all `ObjectStoreAPI` operations required by the Narayana transaction engine. Operations without a semantic equivalent in REPL_SYNC (such as two-phase write operations) are handled as direct committed writes or no-ops, consistent with the always-committed semantics of a synchronously-replicated cache. Implementations should align with what `LRAService` and `LRARecoveryModule` actually invoke rather than providing dead-code stubs.

*Spec: §9.3 | Design: §6 (component interactions)*

---

### 4.4 Infinispan `EmbeddedCacheManager` — the Infinispan integration boundary

`InfinispanObjectStore` holds a static reference to a `Cache<String, byte[]>` instance injected by `InfinispanConfiguration.setCache(cache)`. This is the only point where the cache reference enters the adaptor.

`InfinispanClusterCoordinator` holds a reference to the `EmbeddedCacheManager` and registers `@Listener` on it for view-change events. It does not access any cache directly.

The `EmbeddedCacheManager` is produced as a CDI bean by `InfinispanConfiguration` and injected into both `InfinispanObjectStore` (via `setCache`) and `InfinispanClusterCoordinator` (via `initialize(cacheManager)`).

*Design: §3, §6*

---

### 4.5 JGroups transport — cluster communication

JGroups is used exclusively through the Infinispan-managed transport channel. No direct JGroups API is called by application code except via the Infinispan `@ViewChanged` listener annotation.

The JGroups stack is configurable via `lra.coordinator.jgroups.config` (a path to an XML config file). Two validated configurations are required:

| Configuration | Use case | Key protocols |
|---------------|----------|--------------|
| Default UDP | On-prem, dev, test | `UDP`, `PING`, `FD_SOCK2`, `FD_ALL`, `pbcast.GMS` |
| Kubernetes | K8s / OpenShift production | `TCP`, `KUBE_PING` (headless service), `FD_SOCK2`, `FD_ALL`, `pbcast.GMS` |

The bind address is set via `lra.coordinator.jgroups.bind_addr` (default `127.0.0.1`). For multi-node deployments this must be set to the pod or host IP.

*Design: §2.2 | Spec: §16 | Architecture: AR-02*

---

### 4.6 MicroProfile LRA REST API (unchanged)

The REST API surface is not modified. All eight required endpoints continue to work on every node (spec Appendix A.2). The only observable change from a client's perspective is that any node can handle any request, and the system continues operating through a minority node failure.

---

## 5. Data Model Summary

There is no separate database. All persistent state lives in the Infinispan `lra-objectstore` cache, which is a replicated key–value store. The following summarises the three entry types.

### 5.1 LRA Record

| Field | Detail |
|-------|--------|
| Key pattern | `{objectStoreType}/{uid}` e.g. `/StateManager/BasicAction/LongRunningAction/0_ffff7f000001_abc_123` |
| Value | Opaque byte array — Narayana `OutputObjectState` serialisation of `LongRunningAction`. Contains: LRA status, timeout, parent LRA ID (if nested), list of `LRAParticipantRecord` entries. |
| Lifecycle | Created on LRA start. Updated on every state transition and participant registration. Removed from active area on terminal state reached. |
| Replicated to | All coordinator nodes, synchronously (REPL_SYNC). |

### 5.2 Distributed Lock Entry

| Field | Detail |
|-------|--------|
| Key pattern | `lock/{uid}` |
| Value | UTF-8 bytes of the holding node's node ID (e.g. `"lra-coord-1"`) |
| TTL | Configurable. Default 30 s. Minimum 10 s. (spec §14.2, `lra.coordinator.lock.ttl.seconds`) |
| Lifecycle | Created by `tryLock` (atomic `putIfAbsent`). Removed by `releaseLock` (conditional `remove`). Automatically removed by Infinispan TTL expiry if the holder crashes. |
| Replicated to | All coordinator nodes, synchronously. |

### 5.3 Failed LRA Record

| Field | Detail |
|-------|--------|
| Key pattern | `failed/{objectStoreType}/{uid}` |
| Value | Same serialised byte array as an active LRA record, with status `FailedToClose` or `FailedToCancel`. |
| Lifecycle | Written when recovery exhausts `lra.coordinator.recovery.max.attempts`. Active record is then removed. Never re-processed by recovery scan. |
| Replicated to | All coordinator nodes, synchronously. |

### 5.4 Participant Record (embedded in LRA record)

Participant records are not stored as separate cache entries. They are embedded in the `LongRunningAction` serialised state. Each participant record contains:

| Field | Purpose |
|-------|---------|
| `participantPath` (recovery URL) | Stable identifier; used as key in the participant map; any node can use it to resume callbacks |
| `completeUrl` | Called on LRA close |
| `compensateUrl` | Called on LRA cancel or recovery-driven cancel |
| `statusUrl` | Called by recovery scan to check participant state before re-sending callbacks |
| `forgetUrl` | Called on `FailedToClose` / `FailedToCancel` to allow participant to forget the LRA |
| `afterUrl` | Called after the LRA reaches any terminal state |
| `status` | Per-participant outcome status (`Active`, `Completing`, `Completed`, `FailedToComplete`, etc.) |

Because participant records are embedded in the replicated LRA record, they are available on every coordinator node. Any node can resume callbacks after a failover.

*Spec: §10.4, AC-10.2 | Design: §7.4*

---

## 6. Key Decisions and Rationale

Each decision is traced to its source in the spec or architecture.

### KD-01 — Infinispan REPL_SYNC as the distributed state store

**Decision:** Use `REPL_SYNC` mode (all nodes receive every write synchronously before acknowledgement).

**Rationale:** `REPL_SYNC` satisfies spec NFR-03 (no acknowledged write may be lost on minority failure) and spec §8.3 (quorum acknowledgement before responding to callers). For a cluster of 3–5 nodes, full replication is simpler and more operationally predictable than `DIST_SYNC` (which requires explicit owner management). Every node holds a full replica, making cross-node reads free — a `cache.get` on any node returns the latest value without a network round-trip.

**Trade-off:** Write amplification: every write is broadcast to all nodes. This is acceptable for the expected LRA throughput and cluster size.

*Spec: §9.4, NFR-03, §8.3 | Architecture: AR-01, AD-03 | Design: §2.1*

---

### KD-02 — JGroups view-based leader election (first member in sorted view)

**Decision:** The recovery leader is the node whose JGroups address is first in the sorted membership view.

**Rationale:** Deterministic (all nodes compute the same result from the same view), stateless (no consensus round-trip required), and liveness-safe (any view change triggers immediate recomputation). Satisfies spec §11.2 (safety, liveness, determinism). Already implemented in `InfinispanClusterCoordinator`.

**Trade-off:** The "first in view" rule is simple but not weighted. In Kubernetes, the node that restarts last becomes leader last, which is acceptable. There is no mechanism to prefer a specific node as leader; this is intentional.

*Spec: §11.2, NFR-02 | Architecture: AR-02, AD-02 | Design: §2.2, §5.5*

---

### KD-03 — Distributed lock co-located in `lra-objectstore` cache

**Decision:** Lock entries live in the same cache as LRA records, under a `lock/` prefix. No separate lock service.

**Rationale:** The same `REPL_SYNC` cache already provides linearisable writes. `putIfAbsent` on a REPL_SYNC cache is an atomic CAS. Using the same cache means a single network hop covers both the lock and the data, eliminating the possibility of a split-brain between the lock backend and the data backend. Simplifies the system by removing an infrastructure dependency.

**Trade-off:** Lock entries consume cache capacity and participate in full replication. With at most 10,000 concurrent LRAs (design §2.3), the overhead is negligible.

*Spec: §9.2, §14.2, C-07 | Architecture: AD-03 | Design: §2.3*

---

### KD-04 — State written to Infinispan before participant callbacks are sent

**Decision:** Every LRA state transition is persisted (REPL_SYNC-acknowledged) before the first participant callback for that transition is sent.

**Rationale:** If a coordinator crashes between persisting `Closing` and sending `complete`, the new recovery leader reads the persisted `Closing` state and re-sends `complete`. Participants are idempotent (spec ASSUME-01), so the duplicate is safe. If the write-before-callback order were reversed, no safe recovery is possible: the coordinator would have no record of the callback having been sent.

**Trade-off:** One extra REPL_SYNC write latency per state transition on every close and cancel. Bounded by NFR-01 (p99 ≤ 200 ms cross-node latency).

*Spec: §12.3, Appendix A.1, G-03, ASSUME-01 | Architecture: AD-04 | Design: §5.3*

---

### KD-05 — HA is opt-in; zero cost when disabled

**Decision:** `coordinator-ha-infinispan` is an optional Maven artifact. When absent, or when present but `lra.coordinator.ha.enabled=false`, no Infinispan or JGroups code runs.

**Rationale:** Existing single-node deployments must not be affected. Spec G-06 and C-09 require zero change to non-HA behaviour. Architecture AD-05 requires no startup overhead and no store connections when HA is off (NFR-04).

**Implementation note:** `InfinispanConfiguration.initialize()` checks `lra.coordinator.ha.enabled` as its first step and returns immediately if false. `LRARecoveryModule.ensureHAInitialized()` uses `CDI.current().select(ClusterCoordinationService.class)` with a null-safe fallback so the module works correctly when the bean is absent.

*Spec: G-06, NFR-04, C-09, §16 | Architecture: AD-05 | Design: §2 (activation model)*

---

### KD-06 — Minority partition refuses writes (DENY_READ_WRITES)

**Decision:** The Infinispan partition handling policy is set to `DENY_READ_WRITES`. Minority-partition nodes refuse all cache writes and return an availability exception that the adaptor surfaces as HTTP 503.

**Rationale:** Allowing minority writes risks split-brain: after a partition heals, two conflicting states for the same LRA UID could exist on different sides of the former partition, with no safe merge strategy. Refusing writes on the minority side is the only way to ensure the majority-side state is always authoritative. Satisfies spec C-08, §9.4, §15.3.

**Trade-off:** Clients targeting minority-partition nodes experience write unavailability until the partition heals. This is the correct trade-off for a transaction coordinator (consistency over availability).

*Spec: §9.4, §15.3, C-08, G-08 | Architecture: AD-06 | Design: §2.1*

---

### KD-07 — No session affinity at the load balancer

**Decision:** The load balancer has no affinity configuration. Any request is routed to any coordinator node.

**Rationale:** Any-node routing is fundamental to eliminating the coordinator as a single point of failure (spec G-01). Requiring affinity would partially reintroduce the SPOF problem during node upgrades or failures. Cross-node Infinispan reads are cheap on a REPL_SYNC cache (local replica read, no network hop), so the cost of serving a request for an LRA started on another node is bounded by NFR-01.

*Spec: §10.1, G-01, G-02, ASSUME-02, NFR-01 | Architecture: AD-01 | Design: §4*

---

## 7. Risks and Assumptions

### 7.1 Risks

| ID | Risk | Likelihood | Impact | Mitigation |
|----|------|-----------|--------|-----------|
| R-01 | **Lock TTL shorter than a slow participant callback.** If a `complete` or `compensate` call takes longer than the lock TTL (default 30 s), the lock expires and another node (or the recovery scan) may acquire it and send a duplicate callback. | Low for well-behaved participants; medium for slow/overloaded services. | Duplicate callbacks reach participants (tolerable per ASSUME-01, but adds noise). | Set `lra.coordinator.lock.ttl.seconds` to a value larger than the maximum expected participant round-trip. Validate during integration testing (architecture AR-05). |
| R-02 | **Infinispan replication lag under high write load.** Under heavy LRA throughput, REPL_SYNC synchronous replication to all nodes adds latency on every write. For 3-node clusters this is unlikely to exceed NFR-01 (200 ms p99), but it should be benchmarked. | Low for MVP cluster size (3–5 nodes). | Write latency degrades beyond NFR-01; 503 storm if nodes time out. | Benchmark under representative load before production certification. Consider `DIST_SYNC` for clusters larger than 5 nodes (design §2.1 note). |
| R-03 | **JGroups split-brain detection delay.** JGroups failure detection (FD_SOCK2 / FD_ALL) has a configurable timeout. During this window, a crashed node is not yet removed from the view; the leader role is not transferred. LRAs held by the crashed node remain locked until the TTL expires. | Medium on Kubernetes (pod eviction is fast; network partitions are rarer). | Recovery is delayed by up to `leader.election.timeout` + lock TTL. | Configure FD_ALL timeout to be shorter than the lock TTL so that by the time the new leader starts scanning, the TTL has already or nearly expired. |
| R-04 | **Infinispan global state persistence incompatible across upgrades.** Infinispan global state is written to disk at `lra.coordinator.infinispan.persistent.location`. If the Infinispan version changes between deployments, persisted state may be unreadable. | Low for in-place upgrades; higher for major Infinispan version bumps. | Node fails to start after upgrade. | Use a unique persistent location per node. Test rolling upgrades between consecutive Infinispan versions in CI. |
| R-05 | **Two-node deployment attempted in production.** A two-node cluster produces quorum = 2. A single node failure makes the cluster fully unavailable (DENY_READ_WRITES refuses all writes). An operator may expect 1-of-2 to still serve writes. | Medium (operational mis-configuration). | Full write outage on single node failure. | Document prominently: minimum cluster size is 3 nodes (spec ASSUME-04). Warn at startup if cluster size is detected as 2. |
| R-06 | **MicroProfile LRA TCK failures in HA mode.** HA introduces additional write latency and the cross-node lookup path. Some TCK tests may have tight timing assumptions or may not expect HTTP 503 on lock contention. | Low — TCK tests are designed against the spec; spec allows 503. | Conformance regression. | Run the full TCK against a 3-node embedded cluster in CI before any release. |

### 7.2 Assumptions Carried Forward from the Spec

These assumptions (from spec §3) are inherited by this plan. They are not validated by the coordinator implementation.

| ID | Assumption | Impact if violated |
|----|-----------|-------------------|
| ASSUME-01 | Participants are idempotent. | Duplicate callbacks (e.g. after a crash-and-recovery) may leave participants in an inconsistent state. The coordinator cannot protect against this. |
| ASSUME-02 | Load balancer has no session affinity. | If affinity is configured, a node crash will cause in-flight requests to fail with connection errors rather than being transparently rerouted. |
| ASSUME-03 | Infinispan is reachable from all coordinator nodes over the internal network. | If inter-node Infinispan connectivity is lost (not a network partition, but e.g. firewall mis-configuration), REPL_SYNC writes will fail and the coordinator will return 503 to all clients. |
| ASSUME-04 | Minimum 3 nodes for HA. | 2-node cluster results in full write outage on any single node failure. |
| ASSUME-06 | Wall-clock skew ≤ 1 second. | JGroups FD timeouts and Infinispan TTL expiry are time-based. Significant clock skew may cause premature or delayed lock expiry. |

### 7.3 Open Items Requiring Resolution Before Build Completion

These are carried forward from architecture §8 and spec §4.

| ID | Item | Who must decide |
|----|------|----------------|
| OQ-01 | Maximum acceptable end-to-end recovery time SLA (currently configurable, no hard bound). Must be agreed and added as a NFR before production certification. | Product owner |
| OQ-02 | Minimum replication factor for Infinispan state store relative to cluster size. For REPL_SYNC all nodes hold a replica; this is inherent. For DIST_SYNC (if adopted at larger scale) a replication factor less than cluster size is possible. Policy must be documented. | Architect |
| AR-05 | Lock TTL default value must be validated against actual participant round-trip times in the target deployment environment. | Implementor — during integration testing |

---

*End of Implementation Plan*

---

*Document maintained by the Narayana LRA team. Contributions and feedback via the project issue tracker.*
