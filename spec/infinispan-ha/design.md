# Distributed MicroProfile LRA Coordinator — High-Level Design

**Version:** 1.0-DRAFT  
**Status:** Draft  
**Spec reference:** [distributed-lra-coordinator-spec.md](distributed-lra-coordinator-spec.md) (LRA-DIST-COORD-001)  
**Architecture reference:** [architecture.md](architecture.md)

---

## Table of Contents

1. [Purpose and Scope](#1-purpose-and-scope)
2. [Technology Choices](#2-technology-choices)
3. [Component Map (Concrete)](#3-component-map-concrete)
4. [Main User Flow](#4-main-user-flow)
5. [Main System Flows](#5-main-system-flows)
   - 5.1 [LRA Start](#51-lra-start)
   - 5.2 [Participant Join](#52-participant-join)
   - 5.3 [LRA Close — Cross-Node](#53-lra-close--cross-node)
   - 5.4 [LRA Cancel](#54-lra-cancel)
   - 5.5 [Recovery — Leader Election and Scan](#55-recovery--leader-election-and-scan)
6. [Component Interactions](#6-component-interactions)
7. [Data Movement Across Components](#7-data-movement-across-components)
8. [Traceability to Spec and Architecture](#8-traceability-to-spec-and-architecture)

---

## 1. Purpose and Scope

This document is the High-Level Design (HLD) for the distributed MicroProfile LRA coordinator. It takes the technology-neutral architecture and resolves the open technology choices (architecture AR-01, AR-02) into concrete design decisions using **Infinispan** as the distributed state store and **JGroups** as the cluster membership and transport layer.

The HLD covers the five key flows in the system lifecycle:

1. LRA Start
2. Participant Join
3. LRA Close (including the cross-node case)
4. LRA Cancel
5. Recovery — leader election and recovery scan

The HLD does not define API contracts, database schema, or implementation tasks.

---

## 2. Technology Choices

These choices resolve the open items in architecture §8.

### 2.1 Distributed State Store → Infinispan `lra-objectstore` cache (REPL_SYNC)

Infinispan embedded within each coordinator process provides the distributed state store. A single named cache — `lra-objectstore` — is configured in `REPL_SYNC` (replicated synchronous) mode.

| Property | Value | Reason |
|----------|-------|--------|
| Cache mode | `REPL_SYNC` | Every write is synchronously replicated to all nodes before acknowledgement. Satisfies NFR-03 (quorum write durability) for clusters of 3–5 nodes. |
| Partition handling | `DENY_READ_WRITES` | Minority partition refuses writes. Satisfies spec §9.4, §15.3, C-08, and architecture AD-06. |
| Merge policy | `PREFERRED_ALWAYS` (majority wins) | When a partition heals, the majority-partition state is authoritative. Satisfies spec §15.3. |
| Persistence | Infinispan global state at `lra.coordinator.infinispan.persistent.location` | Satisfies spec §9.5 (durable across full cluster restart), C-13. |
| Cache name | `lra-objectstore` | Shared with the existing `InfinispanObjectStore` implementation on this branch. |

*Architecture references: AR-01, AD-03, AD-06*  
*Spec references: §9.4, §9.5, §15.3*

---

### 2.2 Cluster Membership Service → JGroups (via Infinispan transport)

JGroups is used for cluster membership, failure detection, and leader election. Infinispan's embedded transport uses a JGroups channel; the `InfinispanClusterCoordinator` registers a `@ViewChanged` listener on the `EmbeddedCacheManager` to receive membership events. No separate JGroups channel is required.

| Property | Value | Reason |
|----------|-------|--------|
| Transport | JGroups (embedded via Infinispan `DefaultCacheManager`) | Reuses the same channel already used for cache replication. No additional infrastructure required. |
| Discovery | UDP multicast (default) / `KUBE_PING` (Kubernetes) | UDP multicast is suitable for on-prem. Kubernetes deployments must use `KUBE_PING` with a headless service. |
| Failure detection | `FD_SOCK2` + `FD_ALL` (configurable via `lra.coordinator.jgroups.config`) | Reliable failure detection with configurable timeout. |
| Leader election | JGroups view-based: first member in the sorted view is the recovery leader | Deterministic, stateless, requires no additional consensus round-trip. Satisfies spec §11.2 (safety, liveness, determinism). |
| View-change notification | `@ViewChanged` event on `EmbeddedCacheManager` | Triggers `checkCoordinatorStatus()` in `InfinispanClusterCoordinator`, which notifies the Coordinator Service via `CoordinatorChangeListener`. |

*Architecture references: AR-02, AD-02*  
*Spec references: §8.2, §11.2, §11.3*

---

### 2.3 Distributed Locks → `lra-objectstore` cache, `lock/{uid}` keys

Distributed locks are stored as entries in the same `lra-objectstore` Infinispan cache under a `lock/` key prefix. Lock acquisition uses `cache.putIfAbsent(lockKey, nodeId, ttl, SECONDS)`. Lock release uses `cache.remove(lockKey, nodeId)` (conditional remove).

| Property | Value |
|----------|-------|
| Lock key format | `lock/{uid}` (e.g. `lock/0_ffff7f000001_abc_123`) |
| Lock value | Node ID as UTF-8 bytes |
| Acquire semantics | `putIfAbsent` — atomic CAS, returns existing value or null |
| Release semantics | Conditional `remove(key, expected-value)` — only removes if owner matches |
| TTL | Configurable via `lra.coordinator.lock.ttl.seconds` (default 30 s, minimum 10 s) |
| Same cache as records | Yes — eliminates split-brain between lock and data (architecture AD-03) |

*Architecture references: AD-03*  
*Spec references: §9.2, §14.2*

---

## 3. Component Map (Concrete)

The architecture's five components map to the following concrete classes and infrastructure in this design.

```
 ┌──────────────────────────────────────────────────────────────────────┐
 │                       Coordinator Node                               │
 │                                                                       │
 │  ┌────────────────────────┐   ┌──────────────────────────────────┐   │
 │  │   REST API Layer       │──▶│     Coordinator Service          │   │
 │  │   (JAX-RS: Coordinator │   │     (LRAService +                │   │
 │  │    RecoveryCoordinator)│   │      LRARecoveryModule)          │   │
 │  └────────────────────────┘   └──────────────┬───────────────────┘   │
 │                                              │                        │
 │                               ┌─────────────▼──────────────────┐    │
 │                               │   State Store Adaptor           │    │
 │                               │   (InfinispanObjectStore)       │    │
 │                               │   + HAMetadataStore (tryLock /  │    │
 │                               │     releaseLock)                │    │
 │                               └─────────────┬──────────────────┘    │
 │                                             │                         │
 │  ┌──────────────────────────────────────────▼──────────────────────┐ │
 │  │  Cluster Membership Service                                      │ │
 │  │  (InfinispanClusterCoordinator)                                  │ │
 │  │  — @ViewChanged listener on EmbeddedCacheManager                │ │
 │  │  — notifies LRARecoveryModule via CoordinatorChangeListener     │ │
 │  └──────────────────────────────────────────────────────────────────┘ │
 │                                                                       │
 │  ┌──────────────────────────────────────────────────────────────────┐ │
 │  │  InfinispanConfiguration (CDI @ApplicationScoped)                │ │
 │  │  — produces EmbeddedCacheManager (embedded or WildFly JNDI)     │ │
 │  │  — defines lra-objectstore cache (REPL_SYNC)                    │ │
 │  │  — wires InfinispanObjectStore into Narayana StoreManager       │ │
 │  └──────────────────────────────────────────────────────────────────┘ │
 └──────────────────────────────────────────────────────────────────────┘
                                    │ JGroups transport (TCP/UDP)
                                    │ + Infinispan REPL_SYNC replication
         ┌──────────────────────────▼───────────────────────────────┐
         │         lra-objectstore (Infinispan REPL_SYNC cache)      │
         │                                                            │
         │  Key pattern                   Value                       │
         │  {type}/{uid}           →  serialised LRA state (bytes)   │
         │  lock/{uid}             →  node ID bytes (TTL-bearing)    │
         │  failed/{type}/{uid}    →  serialised FailedTo* state     │
         └────────────────────────────────────────────────────────────┘
                  (replicated copy on every coordinator node)
```

| Architecture component | Concrete class(es) |
|------------------------|-------------------|
| REST API Layer | `Coordinator`, `RecoveryCoordinator` (JAX-RS resources) |
| Coordinator Service | `LRAService`, `LRARecoveryModule` |
| State Store Adaptor | `InfinispanObjectStore` + `HAMetadataStore` interface |
| Cluster Membership Service | `InfinispanClusterCoordinator`, `ClusterCoordinationService` interface |
| Distributed State Store | Infinispan `lra-objectstore` cache (REPL_SYNC), transport via JGroups |
| HA wiring / startup | `InfinispanConfiguration` (CDI `@ApplicationScoped` bean) |

---

## 4. Main User Flow

This section describes the end-to-end experience from the perspective of the developer using the LRA coordinator in a microservice saga.

```
 Developer / Client microservice
        │
        │  1. POST /lra-coordinator
        │     "I want to start a long-running activity"
        │     → Receives LRA ID (URI) in Location header
        │
        │  2. PUT /lra-coordinator/{lraId}
        │     "Register my compensate / complete callbacks"
        │     → Receives participant link header
        │     (Repeated for each microservice joining the saga)
        │
        │  3. Business work proceeds across services
        │     (each service executes its part of the saga)
        │
        │  4a. PUT /lra-coordinator/{lraId}/close     ← success path
        │      "All work done, confirm the saga"
        │      → Coordinator calls complete() on every participant
        │      → LRA moves to Closed
        │
        │  4b. PUT /lra-coordinator/{lraId}/cancel    ← failure path
        │      "Something went wrong, roll back"
        │      → Coordinator calls compensate() on every participant
        │      → LRA moves to Cancelled
        │
        │  5. GET /lra-coordinator/{lraId}/status
        │     "Check the outcome"
        │     → Returns: Closed / Cancelled / FailedToClose / FailedToCancel
        │
        │  [If a coordinator node crashes between steps 3 and 4]
        │  → Recovery leader detects non-terminal LRA in next scan
        │  → Drives it to terminal state automatically
        │  → No manual intervention required
        ▼
```

Key user-facing guarantee: **any step from 1–5 can be handled by any coordinator node**. The developer does not need to track which node started the LRA, and the load balancer requires no affinity configuration.

*Spec references: §2 (deployment scenarios), §10.1 (stateless coordinator), G-01, G-02*

---

## 5. Main System Flows

### 5.1 LRA Start

**Trigger:** Client sends `POST /lra-coordinator`  
**Handling node:** Any coordinator node (routed by load balancer)

```
Client
  │─── POST /lra-coordinator ───────────────────────────▶ REST API Layer
                                                                │
                                                                ▼
                                                       LRAService
                                                       1. Allocate new LRA UID
                                                       2. Create LongRunningAction in memory
                                                       3. Set status: Active
                                                                │
                                                                ▼
                                                       InfinispanObjectStore
                                                       4. cache.put(
                                                            "{type}/{uid}",
                                                            serialised-state
                                                          )
                                                          [REPL_SYNC: blocks until
                                                           all nodes acknowledge]
                                                                │
                                                                ▼
                                                       Infinispan lra-objectstore
                                                       5. Replicates to nodes B and C
                                                          via JGroups transport
                                                       6. Returns acknowledgement
                                                                │
                                                                ▼
                                                       LRAService
                                                       7. Record confirmed durable
                                                                │
                                                                ▼
                                                       REST API Layer
  Client ◀─── 201 Created  ────────────────────────────────────
              Location: http://node-A/lra-coordinator/{uid}
```

**Error path — write failure:**
```
InfinispanObjectStore (step 4) throws / times out
                                                       LRAService
                                                       — Revert: remove LRA from in-memory map
                                                       — Do NOT respond with 201
                                                                │
                                                                ▼
                                                       REST API Layer
  Client ◀─── 500 Internal Server Error ─────────────────────
```

**Data written to store:** `{type}/{uid}` → `[status=Active, timeout, parent=null, participants=[]]`

*Spec: §12.1 — write before respond; Appendix A.1 — rollback on write failure*
*Architecture: AD-04, cross-cutting §6.1, §6.2*

---

### 5.2 Participant Join

**Trigger:** A microservice sends `PUT /lra-coordinator/{lraId}` with a `Link` header containing its callback URLs  
**Handling node:** Any node (may differ from the node that started the LRA)

```
Participant service
  │─── PUT /lra-coordinator/{lraId}
  │    Link: <complete-url>; rel="complete", <compensate-url>; rel="compensate", ...
  │    ──────────────────────────────────────────────▶ REST API Layer
                                                              │
                                                              ▼
                                                     LRAService
                                                     1. lraId not in local memory?
                                                              │
                                                              ▼ (cross-node lookup)
                                                     InfinispanObjectStore
                                                     2. cache.get("{type}/{uid}")
                                                        [reads from local Infinispan
                                                         replica — linearisable]
                                                              │
                                                              ▼
                                                     LRAService
                                                     3. Deserialise → activate in memory
                                                     4. Parse Link header
                                                     5. Add LRAParticipantRecord
                                                        (complete, compensate, status,
                                                         forget, after URLs + recovery URL)
                                                              │
                                                              ▼
                                                     InfinispanObjectStore
                                                     6. cache.put("{type}/{uid}",
                                                            updated-state-with-participant)
                                                        [REPL_SYNC — waits for all nodes]
                                                              │
                                                              ▼
                                                     Infinispan lra-objectstore
                                                     7. Replicates updated record
                                                        to all nodes via JGroups
                                                              │
                                                              ▼
                                                     REST API Layer
  Participant ◀─── 200 OK ──────────────────────────────────────
                 Link: <recovery-url>; rel="lra-participant"
```

**Data written to store:** `{type}/{uid}` → `[..., participants=[{completeUrl, compensateUrl, statusUrl, ...}]]`

*Spec: §12.2 — load from store, write before respond, AC-10.2*  
*Architecture: AD-04, cross-cutting §6.1*

---

### 5.3 LRA Close — Cross-Node

**Trigger:** Client sends `PUT /lra-coordinator/{lraId}/close`  
**Handling node:** Node B (LRA was started on node A — demonstrates the cross-node case)

```
Client
  │─── PUT /lra-coordinator/{lraId}/close ──────────────▶ REST API Layer (node B)
                                                                 │
                                                                 ▼
                                                        LRAService (node B)
                                                        1. lraId not in local memory

                                                        ── Acquire distributed lock ──
                                                                 │
                                                                 ▼
                                                        InfinispanObjectStore (node B)
                                                        2. cache.putIfAbsent(
                                                             "lock/{uid}",
                                                             "node-B",
                                                             ttl=30s
                                                           )
                                                           [CAS — atomic in REPL_SYNC]
                                                           Returns null → lock acquired
                                                                 │
                                                                 ▼
                                                        InfinispanObjectStore (node B)
                                                        3. cache.get("{type}/{uid}")
                                                           [read latest state]
                                                                 │
                                                                 ▼
                                                        LRAService (node B)
                                                        4. Deserialise → activate in memory
                                                        5. Verify status == Active
                                                        6. Transition: Active → Closing

                                                        ── Write-before-callback ──
                                                                 │
                                                                 ▼
                                                        InfinispanObjectStore (node B)
                                                        7. cache.put("{type}/{uid}",
                                                             state[status=Closing])
                                                           [REPL_SYNC — all nodes]
                                                                 │
                                                                 ▼
                                                        LRAService (node B)
                                                        8. For each participant:
                                                           POST {completeUrl}
                                                                 │
                                                           Participant responds 200 OK
                                                                 │
                                                        9. All participants terminal
                                                        10. Transition: Closing → Closed
                                                                 │
                                                                 ▼
                                                        InfinispanObjectStore (node B)
                                                        11. cache.put("{type}/{uid}",
                                                              state[status=Closed])
                                                        12. cache.remove("lock/{uid}",
                                                              "node-B")
                                                            [conditional remove]
                                                                 │
                                                                 ▼
                                                        REST API Layer
 Client ◀─── 200 OK ──────────────────────────────────────────
```

**Error path — write failure (step 7 or step 11) or participant callback failure:**
```
InfinispanObjectStore write throws / participant callback returns non-terminal error:
                                                        LRAService (node B)
                                                        — Revert in-memory state if write failed
                                                        — Release lock unconditionally:
                                                          cache.remove("lock/{uid}", "node-B")
                                                                 │
                                                                 ▼
                                                        REST API Layer
 Client ◀─── 500 Internal Server Error (write failure)
         or  503 Service Unavailable (store unavailable)
```

The lock is released on every exit path — success or failure — to allow recovery to pick up the LRA on the next scan cycle.

**Data written to store (two writes on success):**
1. `{type}/{uid}` → `[status=Closing, ...]`
2. `{type}/{uid}` → `[status=Closed, participants=[all-Completed]]`
3. `lock/{uid}` entry removed

*Spec: §12.3, §10.3, §14.2, AC-12.2, Appendix A.1 — rollback on write failure*
*Architecture: AD-01, AD-03, AD-04, cross-cutting §6.1*

---

### 5.4 LRA Cancel

Cancel follows the same flow as Close (§5.3) with two differences:

| Step | Close | Cancel |
|------|-------|--------|
| Transition | Active → Closing | Active → Cancelling |
| Callback sent to each participant | `complete` | `compensate` |
| Terminal state (success) | Closed | Cancelled |
| Terminal state (participant failure) | FailedToClose | FailedToCancel |

All lock acquire, write-before-callback, and cross-node lookup steps are identical.

*Spec: §12.3, Appendix A.1*

---

### 5.5 Recovery — Leader Election and Scan

This flow covers two sub-scenarios: **initial leader election on startup** and **re-election after a crash**.

#### 5.5.1 Startup and Initial Leader Election

```
                    [All three nodes start up]

Node A                    Node B                    Node C
   │                         │                         │
   │── InfinispanConfiguration.initialize() ───────────│
   │   EmbeddedCacheManager starts                      │
   │   JGroups channel opens                            │
   │   lra-objectstore cache joins cluster              │
   │                         │                         │
   │◀────────────────── JGroups VIEW event ────────────▶│
   │   View = {A, B, C}       │   View = {A, B, C}     │
   │                         │                         │
   │   InfinispanClusterCoordinator.checkCoordinatorStatus()
   │   first member in view == A?
   │                         │
   A: YES → isCoordinator=true
   B: NO  → isCoordinator=false
   C: NO  → isCoordinator=false
   │
   │── onBecameCoordinator() ──▶ LRARecoveryModule (node A)
                                       │
                                       ▼
                              Schedule immediate recovery scan
                              (does not wait for next interval)
```

#### 5.5.2 Periodic Recovery Scan (Leader Node A)

```
LRARecoveryModule (node A — recovery leader)
   │
   │ [Scan interval elapses, or onBecameCoordinator triggered]
   │
   ▼
InfinispanObjectStore
1. cache.keySet() filtered by "{type}/" prefix
   → enumerates all LRA UIDs across all nodes
   │
   FOR EACH uid:
   │
   ▼
InfinispanObjectStore
2. cache.putIfAbsent("lock/{uid}", "node-A", ttl=30s)
   [if returns non-null → another node holds lock → SKIP, log DEBUG]
   │
   ▼
InfinispanObjectStore
3. cache.get("{type}/{uid}")
   [linearisable read — latest state]
   │
   ▼
LRARecoveryModule
4. Deserialise record
5. If status is terminal → cache.remove("lock/{uid}", "node-A"); CONTINUE
6. For each non-terminal participant:
   GET {statusUrl}
   → Participant responds with its current status
   │
7. Based on LRA intended outcome and participant status:
   POST {completeUrl}  or  POST {compensateUrl}
   │
   ▼
InfinispanObjectStore
8. cache.put("{type}/{uid}", updated-state)
   [REPL_SYNC — after each participant state change]
   │
9. If all participants terminal:
   cache.put("{type}/{uid}", final-terminal-state)
   cache.remove("lock/{uid}", "node-A")
   │
   [If max attempts exceeded:]
   ▼
InfinispanObjectStore
10. cache.put("failed/{type}/{uid}", FailedTo*-state)
    cache.remove("{type}/{uid}")
    cache.remove("lock/{uid}", "node-A")
```

**Timeout-driven cancellation:** During step 1 (enumeration), the scan MUST also check each Active LRA's timeout field. If `current_time > lra.timeout`, the scan transitions the LRA to `Cancelling` (persisting the state) and then drives compensate callbacks, exactly as for a normal cancel. This is the implementation of the `Active ──(timeout)──▶ Cancelling` transition from the state machine (spec Appendix A.1).

*Spec: §13.1, §13.2, §13.3, §13.4, Appendix A.1 — timeout transition*

#### 5.5.3 Leader Crash and Re-election

```
Node A (recovery leader)
   │
   │ [crashes mid-scan, holding lock on lra-007]
   │ lock/lra-007 = "node-A" (TTL=30s still counting)

JGroups (on nodes B and C)
   │
   │ [FD_SOCK2 / FD_ALL detects A is unreachable]
   ▼
New JGroups VIEW = {B, C}
   │
InfinispanClusterCoordinator (node B)
   │
   │ checkCoordinatorStatus():
   │   first member in view == B → isCoordinator=true
   │
   ▼
onBecameCoordinator() → LRARecoveryModule (node B)
   │
   │ Triggers immediate recovery scan
   │
   FOR EACH uid in store:
   │
   │ try lock/lra-007:
   │   cache.putIfAbsent("lock/lra-007", "node-B", ttl=30s)
   │
   │ [if TTL not yet expired → returns "node-A" → SKIP for now]
   │ [after lock TTL expires → putIfAbsent returns null → ACQUIRED]
   │
   ▼
Node B resumes recovery of lra-007:
   cache.get("{type}/lra-007") → state from Infinispan replica
   drive to terminal state
   cache.put("{type}/lra-007", terminal-state)
   cache.remove("lock/lra-007", "node-B")
```

*Spec: §11.2, §11.3, §13.1, §15.1, AC-13.1, NFR-02*  
*Architecture: AD-02*

---

## 6. Component Interactions

This table summarises every cross-component call in the five flows above.

| From | To | Interaction | Flow |
|------|----|-------------|------|
| REST API Layer | Coordinator Service (`LRAService`) | Delegate start/join/close/cancel/status | All normal-path flows |
| Coordinator Service | State Store Adaptor (`InfinispanObjectStore`) | write(uid, state) — synchronous quorum write | Start, Join, Close, Cancel, Recovery |
| Coordinator Service | State Store Adaptor | read(uid) — linearisable read | Join (cross-node), Close/Cancel (cross-node), Recovery |
| Coordinator Service | State Store Adaptor | try-lock(uid) — CAS putIfAbsent with TTL | Close, Cancel, Recovery |
| Coordinator Service | State Store Adaptor | release-lock(uid) — conditional remove | Close, Cancel, Recovery |
| Coordinator Service | State Store Adaptor | enumerate(type) — keySet scan | Recovery scan |
| State Store Adaptor | Infinispan `lra-objectstore` cache | All cache operations | All flows |
| Infinispan cache | JGroups transport | Synchronous replication of every write | All flows (transparent) |
| JGroups | `InfinispanClusterCoordinator` | `@ViewChanged` event on membership change | Leader election |
| `InfinispanClusterCoordinator` | `LRARecoveryModule` | `onBecameCoordinator()` / `onLostCoordinator()` via `CoordinatorChangeListener` | Leader election, crash recovery |
| `LRARecoveryModule` | Participant services (external) | HTTP GET `{statusUrl}` — check participant state | Recovery scan |
| `LRARecoveryModule` | Participant services (external) | HTTP POST `{completeUrl}` or `{compensateUrl}` — drive to terminal | Recovery scan, Close, Cancel |
| `InfinispanConfiguration` | `InfinispanObjectStore` | `setCache(cache)` — wires Infinispan cache into adaptor at startup | Startup |
| `InfinispanConfiguration` | Narayana `StoreManager` | Registers `InfinispanObjectStore` as ObjectStore backend | Startup |

**Boundary rule enforced throughout:** `LRAService` and the REST API Layer never call the Infinispan cache directly. All store interactions go through `InfinispanObjectStore`.

*Architecture: §4 (component boundaries), cross-cutting §6.4*

---

## 7. Data Movement Across Components

### 7.1 LRA Record

An LRA record is the serialised state of one `LongRunningAction`. It is the primary data artefact in the system.

| Stage | Location | Format |
|-------|----------|--------|
| Created | `LRAService` in-memory map | `LongRunningAction` object |
| Written to store | Infinispan `lra-objectstore` | Key: `{type}/{uid}`, Value: opaque byte array (Narayana serialised state) |
| Replicated | All coordinator nodes | Identical byte array copies in each node's Infinispan replica |
| Read by non-originating node | `InfinispanObjectStore.read(uid)` | Byte array → deserialised `LongRunningAction` → activated into local memory |
| Updated after state transition | `InfinispanObjectStore.write(uid, state)` | New serialised state replicated to all nodes (REPL_SYNC) |
| Terminal — removed from active area | `InfinispanObjectStore.remove(uid)` | Entry deleted from `lra-objectstore` cache |
| Failed (exhausted recovery) | `lra-objectstore`, key prefix `failed/` | Preserved indefinitely under distinct prefix; excluded from recovery scan |

### 7.2 Distributed Lock Entry

| Stage | Location | Format |
|-------|----------|--------|
| Created on lock acquire | Infinispan `lra-objectstore` | Key: `lock/{uid}`, Value: node ID (UTF-8 bytes), TTL: 30 s default |
| Replicated | All nodes (REPL_SYNC) | Identical entry on all replicas |
| Expires automatically | Infinispan TTL mechanism | Removed without explicit action if lock holder crashes |
| Released on lock release | Conditional `cache.remove(lockKey, nodeId)` | Removed only if current value matches releasing node's ID |

### 7.3 Cluster Membership View

| Stage | Location | Movement |
|-------|----------|---------|
| Detected | JGroups channel (FD_SOCK2 / FD_ALL) | Heartbeat/ping over TCP/UDP between coordinator nodes |
| Propagated | JGroups GMS (Group Membership Service) | Broadcast to all nodes in the cluster |
| Consumed | `InfinispanClusterCoordinator.@ViewChanged` listener | Calls `checkCoordinatorStatus()` — compares local address to first member in view |
| Notified to coordinator | `CoordinatorChangeListener.onBecameCoordinator()` / `onLostCoordinator()` | Synchronous callback into `LRARecoveryModule` |

### 7.4 Participant Callbacks

Participant callback URLs (complete, compensate, status, forget, after) are stored as part of the LRA record in Infinispan. They are **not** stored in a separate data structure. When any node drives a close or cancel, it reads the callback URLs from the Infinispan record and makes HTTP calls directly to the participant service.

```
Infinispan record
  participantRecords[]:
    - participantPath      (recovery URL — stored so any node can resume)
    - completeUrl
    - compensateUrl
    - statusUrl
    - forgetUrl
    - afterUrl
    - status               (one of: Active, Compensating, Compensated, FailedToCompensate,
                                    Completing, Completed, FailedToComplete
                            — full MicroProfile LRA ParticipantStatus enum)
```

This means participant registration data is replicated to all nodes at the time of registration (§5.2 step 6), ensuring any node can send callbacks after a failover.

*Spec: §10.4, AC-10.2*

---

## 8. Traceability to Spec and Architecture

| Design element | Spec reference | Architecture reference |
|----------------|---------------|----------------------|
| Infinispan REPL_SYNC as state store | §9.4 (linearisable writes), §9.5 (durability), NFR-03 | AR-01 resolved, AD-03 |
| JGroups @ViewChanged for leader election | §11.2 (safety, liveness, determinism), §8.2 | AR-02 resolved, AD-02 |
| DENY_READ_WRITES partition policy | §9.4, §15.3, C-08 | AD-06 |
| `cache.putIfAbsent` for lock acquire | §14.2 (atomic CAS), C-07 | AD-03 |
| Conditional `cache.remove` for lock release | §14.2 (conditional remove) | AD-03 |
| Lock TTL default 30 s, min 10 s | §14.2, §16 | AR-05 |
| REPL_SYNC blocks until all nodes acknowledge | §8.3 (quorum), NFR-03, C-03 | §6.2 |
| Write-before-respond on all state transitions | §12.1, §12.2, §12.3, §13.2 rule 3, C-02 | AD-04, §6.1 |
| Cross-node LRA lookup via Infinispan read | §10.3, AC-9.2, AC-10.1 | AD-01 |
| Participant URLs stored in LRA record (replicated) | §10.4, AC-10.2 | §7.4 |
| HA disabled → no Infinispan initialisation | G-06, NFR-04, C-09 | AD-05, §6.5 |
| Recovery scan by recovery leader only | §13.1, §13.2, C-05 | AD-02 |
| TTL expiry allows re-acquisition after crash | §14.2, AC-9.5, AC-13.1 | AD-03 |
| failed/ key prefix for exhausted LRAs | §13.4, C-11 | §7.1 |
| `InfinispanConfiguration` activates only when HA enabled | §16 (`lra.coordinator.ha.enabled`), G-06 | AD-05 |
| JGroups KUBE_PING for Kubernetes discovery | §2 (HA cluster deployment scenario), ASSUME-02 | AR-02 |

---

*End of High-Level Design*

---

*Document maintained by the Narayana LRA team. Contributions and feedback via the project issue tracker.*
