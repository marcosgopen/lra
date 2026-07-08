# Universal Distributed MicroProfile LRA Coordinator — Implementation Specification

**Version:** 1.0  
**Status:** Draft  
**Spec ID:** LRA-UNIV-001  
**Applicable MicroProfile LRA version:** 2.0+  
**Supersedes:** n/a (new document; complements LRA-DIST-COORD-001)

---

## Table of Contents

1. [Purpose and Intended Audience](#1-purpose-and-intended-audience)
2. [Design Principles](#2-design-principles)
3. [Terminology](#3-terminology)
4. [System Overview](#4-system-overview)
5. [Coordinator Node](#5-coordinator-node)
6. [Cluster Membership and Node Identity](#6-cluster-membership-and-node-identity)
7. [Distributed State Store Contract](#7-distributed-state-store-contract)
8. [LRA Record Model](#8-lra-record-model)
9. [Distributed Lock Protocol](#9-distributed-lock-protocol)
10. [Request Routing](#10-request-routing)
11. [LRA Lifecycle — State Machine](#11-lra-lifecycle--state-machine)
12. [LRA Lifecycle — Distributed Operations](#12-lra-lifecycle--distributed-operations)
13. [Leader Election and Recovery Authority](#13-leader-election-and-recovery-authority)
14. [Recovery Protocol](#14-recovery-protocol)
15. [Failure Scenarios](#15-failure-scenarios)
16. [Observability Requirements](#16-observability-requirements)
17. [Configuration Reference](#17-configuration-reference)
18. [Conformance and Testing](#18-conformance-and-testing)
19. [Appendix A — REST API Surface](#appendix-a--rest-api-surface)
20. [Appendix B — Key Namespace](#appendix-b--key-namespace)
21. [Appendix C — Sequence Diagrams](#appendix-c--sequence-diagrams)
22. [Appendix D — Conformance Checklist](#appendix-d--conformance-checklist)

---

## 1. Purpose and Intended Audience

This document is a **universal implementation specification** for a distributed, multi-node MicroProfile LRA coordinator. It is technology-neutral: it prescribes the observable properties, data contracts, and failure semantics that any conformant implementation must satisfy, without mandating any specific programming language, distributed systems library, or deployment platform.

### 1.1 Intended Readers

| Reader | Expected Use |
|--------|-------------|
| **Implementors** | Build a complete distributed coordinator from this document. |
| **Evaluators** | Assess whether an existing implementation is conformant. |
| **Platform engineers** | Understand operational contracts and deployment requirements. |
| **Test engineers** | Derive acceptance tests directly from the requirements in §18. |

### 1.2 Relationship to Other Documents

| Document | Role |
|----------|------|
| MicroProfile LRA 2.0 specification | Defines participant API, annotations, and REST API surface. This spec extends it to multi-node operation. |
| LRA-DIST-COORD-001 | Feature-level requirements for the Narayana reference implementation. This document is the universal form of those requirements. |

### 1.3 Scope

- Coordinator cluster topology, membership management, and node identity.
- LRA state model, ownership rules, and routing behaviour.
- Distributed state store interface contract.
- Distributed lock protocol.
- Leader election scoped to recovery operations.
- Recovery protocol for crash-restart, graceful shutdown, and network partition.
- Observability (structured log events and metrics).
- Conformance test matrix.

### 1.4 Out of Scope

- Client-side LRA SDK or annotation processing.
- MicroProfile LRA participant implementation.
- Specific distributed store or cluster membership technology selection.
- Performance benchmarking methodology.
- Internal code organisation, module structure, or build tooling.

---

## 2. Design Principles

The following principles govern every requirement in this specification. When requirements conflict, the principle with the lower number takes precedence.

| ID | Principle |
|----|-----------|
| **P-01** | **Correctness before availability.** In any ambiguous situation, the coordinator must prefer returning an error to returning incorrect state. |
| **P-02** | **No silent data loss.** Every LRA transition and every participant registration must be durably persisted before a success response is returned to any caller. |
| **P-03** | **Single point of failure eliminated.** The system must continue processing new and existing LRAs when a strict minority of coordinator nodes are unavailable, without any manual intervention. |
| **P-04** | **Stateless routing.** Any coordinator node must be able to serve any request for any LRA. No session affinity between an LRA and a coordinator node is required or maintained. |
| **P-05** | **Idempotent recovery.** Every recovery operation must be safe to retry. Participants are assumed to implement idempotent callbacks (per MicroProfile LRA §6.3). |
| **P-06** | **Technology neutrality.** This specification defines interfaces, not implementations. A team choosing any conformant distributed store and cluster membership layer must be able to build a fully compliant coordinator. |
| **P-07** | **Backwards compatibility.** HA mode must not alter the MicroProfile LRA REST API surface, state machine, or participant callback semantics visible to external callers. |
| **P-08** | **Additive HA.** Single-node mode must continue to work with zero dependency on any distributed infrastructure. HA is a strictly additive capability. |

---

## 3. Terminology

| Term | Definition |
|------|-----------|
| **LRA** | Long Running Action. A distributed saga coordinated by a coordinator. |
| **LRA record** | The durable representation of a single LRA, including its current state, participants, and metadata. |
| **Coordinator node** | A single process running the coordinator service, REST API, state store adaptor, and cluster membership client. |
| **Cluster** | The set of coordinator nodes that share a distributed state store and a cluster membership view. |
| **State store** | The distributed, replicated persistence layer shared by all coordinator nodes. |
| **Quorum** | The minimum number of cluster nodes (strictly more than half) that must acknowledge a write before it is considered durable. |
| **Recovery leader** | The single coordinator node currently authorised to run cluster-wide recovery scans. |
| **Participant** | A service that registers with the coordinator to receive `complete` or `compensate` callbacks. |
| **Distributed lock** | A cluster-wide, TTL-bearing mutual exclusion token stored in the state store. |
| **Lock TTL** | The time-to-live after which an unrenewed lock entry is automatically expired by the state store. |
| **In-memory state** | The coordinator's local, non-persistent view of active LRA records, used for fast local access. |
| **Activation** | The act of loading an LRA record from the state store into a coordinator node's in-memory state. |
| **Minority partition** | A set of nodes that cannot form quorum because fewer than half of the known cluster nodes are reachable. |
| **Majority partition** | A set of nodes that can form quorum. |
| **CAS** | Compare-and-swap: an atomic operation that sets a value only when the current value matches an expected value. |
| **Terminal state** | Any LRA state from which no further transition is possible: `Closed`, `Cancelled`, `FailedToClose`, `FailedToCancel`. |

---

## 4. System Overview

### 4.1 Cluster Topology

A distributed coordinator cluster consists of **N ≥ 3** coordinator nodes deployed behind a load balancer. There is no session affinity; the load balancer routes each request to any available node.

```
                  ┌───────────────────────────────────────────────────┐
                  │   Client microservices (participants & initiators) │
                  └───────────────────────┬───────────────────────────┘
                                          │  MicroProfile LRA REST API
                              ┌───────────▼──────────────┐
                              │  Load Balancer / Ingress  │
                              │  (no session affinity)    │
                              └───┬──────────┬──────────┬─┘
                                  │          │          │
                         ┌────────▼──┐  ┌────▼───┐  ┌──▼──────┐
                         │ Coord (A) │  │Coord(B)│  │Coord (C)│
                         └────────┬──┘  └────┬───┘  └──┬──────┘
                                  │          │          │
                         ┌────────▼──────────▼──────────▼────────┐
                         │        Distributed State Store         │
                         │  (replicated, linearisable, durable)   │
                         └────────────────────────────────────────┘
```

### 4.2 Node Internal Architecture

Each coordinator node contains four logical components:

```
 ┌──────────────────────────────────────────────────────────────────────┐
 │                          Coordinator Node                            │
 │                                                                      │
 │  ┌─────────────────┐    ┌────────────────────────────────────────┐  │
 │  │  REST API Layer  │───▶│          Coordinator Service           │  │
 │  │  (MicroProfile  │    │  • LRA lifecycle and state machine     │  │
 │  │   LRA REST API) │    │  • In-memory LRA map                   │  │
 │  └─────────────────┘    │  • Participant callback dispatch       │  │
 │                          └───────────────┬────────────────────────┘  │
 │                                          │                           │
 │                          ┌───────────────▼────────────────────────┐  │
 │                          │         State Store Adaptor             │  │
 │                          │  write / read / remove / lock / enum   │  │
 │                          └───────────────┬────────────────────────┘  │
 │                                          │                           │
 │  ┌───────────────────────────────────────▼────────────────────────┐  │
 │  │              Cluster Membership Service                         │  │
 │  │  • Node view  • Failure detection  • Leader election           │  │
 │  └─────────────────────────────────────────────────────────────────┘  │
 └──────────────────────────────────────────────────────────────────────┘
                              │
             ┌────────────────▼────────────────────────────────┐
             │           Distributed State Store                │
             │  LRA records  |  Lock entries  |  Failed area   │
             └─────────────────────────────────────────────────┘
```

**Component boundary rules (mandatory):**

- The REST API Layer must not access the state store directly.
- The Coordinator Service must access the state store only through the State Store Adaptor.
- The Cluster Membership Service must not modify LRA records.
- Only the State Store Adaptor communicates with the Distributed State Store.

---

## 5. Coordinator Node

### 5.1 Startup Sequence

An implementation must perform the following steps, in order, before accepting requests.

1. **Initialise node identity.** Determine the node ID (§6.2).
2. **Initialise cluster membership.** Join the cluster membership view. Wait until the local node appears in the view.
3. **Connect to the state store.** Establish the state store connection. The adaptor must not return from initialisation until the connection is confirmed live.
4. **Rebuild in-memory state.** If HA mode is enabled, read all non-terminal LRA records from the state store and activate them into the in-memory map.
5. **Start the REST API.** Begin accepting HTTP requests only after steps 1–4 complete.
6. **Trigger recovery if elected leader.** If this node is the recovery leader at startup, schedule an immediate recovery scan (§14.2).

**MUST NOT** accept requests before step 5. A readiness probe must reflect whether step 5 has been reached.

### 5.2 Shutdown Sequence

On graceful shutdown:

1. Remove the node from the load balancer pool (or set the readiness probe to unhealthy) before beginning shutdown.
2. Allow in-flight requests to complete for up to a configurable drain timeout (`lra.coordinator.shutdown.drain.seconds`, default 30 s).
3. Release all distributed locks held by this node.
4. Disconnect from the cluster membership view.
5. Disconnect from the state store.

After an ungraceful shutdown, held locks expire automatically via TTL (§9.4).

### 5.3 HA Mode Toggle

When `lra.coordinator.ha.enabled=false`:

- Steps 2 (cluster membership) and 4 (state store rebuild from remote) are skipped.
- The State Store Adaptor uses the local persistent store only.
- The Cluster Membership Service must not be initialised.
- There must be no classpath, startup-time, or runtime dependency on any distributed infrastructure component.

---

## 6. Cluster Membership and Node Identity

### 6.1 Membership View

The cluster membership view is an ordered, consistent list of all known live coordinator nodes. Every coordinator node must maintain a current view.

**Required properties:**

- **Liveness detection.** A node that stops responding to heartbeats within a configurable interval (`lra.coordinator.heartbeat.timeout.ms`, default 5000 ms) must be removed from the view.
- **View consistency.** All nodes must converge to the same membership view within a configurable interval after any node join or departure.
- **Deterministic ordering.** For any given view, all nodes must compute the same sorted node ordering. This ordering is used for leader election (§13).
- **Minimum cluster size enforcement.** When the cluster drops below the minimum configured size (`lra.coordinator.cluster.min.nodes`, default 3), write operations must be rejected (§15.3).

### 6.2 Node ID

Each coordinator node must have a cluster-unique, stable string identifier used to:
- Embed the originating node identity in LRA URIs.
- Identify the owner of a distributed lock entry.
- Determine recovery leader position in the sorted view.

**Resolution order (highest to lowest priority):**

1. System property `lra.coordinator.node.id`.
2. Environment variable `HOSTNAME` (set automatically in Kubernetes/OpenShift `StatefulSet` pods).
3. Derived fallback: `"lra-coord-" + <current process PID>`.

**Requirements:**

- The node ID must not be empty.
- The node ID must be stable for the lifetime of the process.
- The node ID must not contain characters that are invalid in a URL path segment (RFC 3986 §3.3).

### 6.3 Quorum Definition

Quorum = `floor(N/2) + 1`, where N is the total number of known nodes in the current membership view.

A write operation is durable when it has been acknowledged by at least quorum nodes. An implementation must use the state store's own quorum mechanism (e.g. `REPL_SYNC` in Infinispan, quorum reads/writes in etcd) rather than implementing a separate quorum layer.

---

## 7. Distributed State Store Contract

This section defines the interface contract that any conformant state store adaptor must implement. It does not prescribe a specific technology.

### 7.1 Operations

The State Store Adaptor must expose the following operations to the Coordinator Service:

| Operation | Signature | Description |
|-----------|-----------|-------------|
| `write` | `write(key: String, value: bytes): void` | Write or overwrite a key-value entry. Must complete synchronously after quorum acknowledgement. |
| `read` | `read(key: String): bytes \| null` | Read the current value of a key. Must return the latest committed value (linearisable read). |
| `remove` | `remove(key: String): boolean` | Remove a key. Returns `true` if the key existed. |
| `conditionalRemove` | `conditionalRemove(key: String, expectedValue: bytes): boolean` | Remove a key only if its current value equals `expectedValue`. Used for lock release. |
| `putIfAbsent` | `putIfAbsent(key: String, value: bytes, ttlSeconds: int): bytes \| null` | Write the entry only if the key does not already exist. Returns `null` on success (entry was absent), or the existing value on failure. Must be atomic (CAS). |
| `enumerate` | `enumerate(keyPrefix: String): Set<String>` | Return all keys currently stored under the given prefix. Must return a stable snapshot of the key set at the time of the call. Must include keys written by any coordinator node. |
| `containsKey` | `containsKey(key: String): boolean` | Return `true` if the key exists. |

### 7.2 Consistency Requirements

| Requirement | Detail |
|-------------|--------|
| **Synchronous quorum writes** | `write` must not return until the store has confirmed replication to at least quorum nodes. Returning before quorum acknowledgement is a conformance violation. |
| **Linearisable reads on state-transition paths** | `read` calls made on the `close`, `cancel`, `activate`, and `recovery` paths must observe the latest committed value. Stale cached reads are not acceptable on these paths. |
| **Atomic CAS** | `putIfAbsent` must be atomic. Concurrent `putIfAbsent` calls for the same key from different nodes must result in exactly one succeeding. |
| **Snapshot enumeration** | `enumerate` must return a consistent point-in-time snapshot. Concurrent writes during enumeration must not cause missed or duplicate keys in the result. |

### 7.3 Durability Requirements

| Requirement | Detail |
|-------------|--------|
| **Persistence across full restart** | All entries written before a coordinated full cluster shutdown must be readable after all nodes have restarted. |
| **Crash durability** | Entries acknowledged to quorum must not be lost if a minority of nodes crash simultaneously. |
| **Replication factor** | The state store replication factor must be at least `quorum` (i.e. at least `floor(N/2) + 1`). Deployments where the replication factor is lower than quorum do not satisfy this spec's durability guarantees. |

### 7.4 Error Classification

The State Store Adaptor must communicate errors to the Coordinator Service using two distinct error classes:

| Error Class | When to Raise | HTTP Mapping |
|-------------|--------------|--------------|
| `StoreUnavailableException` | The store is temporarily unreachable or in a degraded partition (e.g. `AvailabilityException` in Infinispan, quorum loss in etcd). | HTTP 503 |
| `StoreException` | Any other store error (e.g. serialisation failure, internal error). | HTTP 500 |

The Coordinator Service must not conflate these two error classes. Callers receiving HTTP 503 know the failure is transient and may retry.

### 7.5 Key Namespace

See [Appendix B](#appendix-b--key-namespace) for the full key format specification.

---

## 8. LRA Record Model

### 8.1 Required Fields

An LRA record must persistently store the following fields:

| Field | Type | Description |
|-------|------|-------------|
| `lraId` | URI | Globally unique identifier for this LRA, including the originating node ID. |
| `state` | Enum | Current state in the LRA state machine (§11). |
| `startTime` | Epoch ms | When the LRA was started. |
| `timeoutMs` | long | LRA timeout in milliseconds. Zero means no timeout. |
| `participants` | List | Registered participant descriptors (§8.2). |
| `parentId` | URI \| null | For nested LRAs, the parent LRA identifier. |
| `recoveryAttempts` | int | Number of times recovery has been attempted for this LRA. |
| `closingNodeId` | String \| null | Node ID of the coordinator currently closing or cancelling this LRA. Set when acquiring the closing lock; cleared on release. |

### 8.2 Participant Descriptor

Each participant entry within an LRA record must contain:

| Field | Type | Description |
|-------|------|-------------|
| `participantId` | URI | The participant's compensation link. Used to deduplicate on re-registration. |
| `compensateURI` | URI | Endpoint to call on cancel / compensate. |
| `completeURI` | URI | Endpoint to call on close / complete. |
| `statusURI` | URI \| null | Endpoint to query participant status. May be absent. |
| `forgetURI` | URI \| null | Endpoint to call after a `FailedToComplete` or `FailedToCompensate`. |
| `afterURI` | URI \| null | Endpoint called regardless of LRA outcome. |
| `status` | Enum | Last known participant status (`Active`, `Completing`, `Compensating`, `Completed`, `Compensated`, `FailedToComplete`, `FailedToCompensate`). |

### 8.3 Serialisation

The LRA record serialisation format is implementation-defined. The implementation must satisfy the following constraints:

- The serialised form must be deterministic: the same record state must always produce the same byte sequence given the same ordered participant list.
- The serialised form must be versioned. A version mismatch on deserialisation must cause the adaptor to raise `StoreException` with a descriptive message.
- Serialisation and deserialisation must be tolerant of additional unknown fields (forward compatibility).

---

## 9. Distributed Lock Protocol

### 9.1 Purpose

The distributed lock prevents two coordinator nodes from concurrently closing or cancelling the same LRA. Without mutual exclusion, duplicate participant callbacks could be sent for the same state transition.

### 9.2 Lock Key Format

```
lock/{uid}
```

where `{uid}` is the unique part of the LRA ID (the path segment after the last `/` in the LRA URI). See Appendix B.

### 9.3 Lock Acquire (`tryLock`)

```
tryLock(uid: Uid): boolean
```

1. Construct the lock key: `lock/{uid}`.
2. Construct the lock value: the current node's node ID as UTF-8 bytes.
3. Call `putIfAbsent(lockKey, nodeIdBytes, ttlSeconds)`.
4. If the return is `null` (key was absent), the lock is acquired — return `true`.
5. If the return is non-null (key was already present), the lock is held by another node — return `false`.
6. If the store raises `StoreUnavailableException`, propagate it as-is. Do not return `false`.

**TTL** is configured via `lra.coordinator.lock.ttl.seconds` (default 30 s, minimum 10 s). The TTL must be at least as long as the maximum expected participant callback round-trip time.

### 9.4 Lock Release (`releaseLock`)

```
releaseLock(uid: Uid): void
```

1. Construct the lock key: `lock/{uid}`.
2. Construct the expected value: the current node's node ID as UTF-8 bytes.
3. Call `conditionalRemove(lockKey, nodeIdBytes)`.
4. If the key did not exist or the value did not match, log a WARN (this is expected after a TTL expiry during recovery).
5. Do not throw on a no-match; the lock may have expired legitimately.

**The lock MUST be released on all exit paths** (success and failure) from any `close`, `cancel`, or `tryRecoverLRA` operation. Implementations must use a `finally`-equivalent construct to guarantee release.

### 9.5 Lock Expiry and Crash Recovery

If the coordinator holding a lock crashes, the lock entry expires after its TTL. The next recovery scan will `tryLock` on the affected LRA and resume processing. The gap between crash and TTL expiry is the maximum additional delay before recovery begins.

The lock TTL should be set conservatively: long enough to survive any participant callback under normal conditions, short enough that crash recovery does not stall for excessive periods.

---

## 10. Request Routing

### 10.1 Stateless Coordinator Principle

Any coordinator node must be able to serve any request for any LRA, regardless of which node created the LRA.

**Implementation rule:** If an LRA is not present in the node's in-memory map, the node must attempt to *activate* the LRA from the state store before processing the request:

```
activate(lraId):
  record = stateStore.read(keyFor(lraId))
  if record == null:
    raise LRANotFoundException(lraId)
  inMemoryMap.put(lraId, deserialise(record))
```

The activation read must be linearisable (§7.2).

### 10.2 Node ID Embedding in LRA URIs

The LRA URI must embed the node ID of the node that created the LRA, so that inter-node routing hints are available without a lookup:

```
http://{coordinator-host}:{port}/lra-coordinator/{node-id}/{uid}
```

**Example:**

```
http://lra-coordinator:8080/lra-coordinator/node-1/0_ffff7f000001_abc_6789abcd
```

The `{node-id}` segment is informational only. No implementation must require routing to the originating node; stateless routing (§10.1) takes precedence.

### 10.3 Participant Re-registration Deduplication

When a participant registers for an LRA that already contains an entry with the same `participantId`, the registration must be treated as an idempotent update (the existing entry is replaced). The coordinator must not add a duplicate participant entry.

### 10.4 No Inter-coordinator HTTP Routing

Coordinator nodes must not forward requests to other coordinator nodes via HTTP. Every node is capable of serving every request via the state store (§10.1). Introducing inter-coordinator routing creates a latency chain and reintroduces a partial SPOF.

---

## 11. LRA Lifecycle — State Machine

### 11.1 States

| State | Terminal | Description |
|-------|---------|-------------|
| `Active` | No | LRA is accepting participant registrations and normal operation. |
| `Closing` | No | Close has been requested; `complete` callbacks are being sent to participants. |
| `Cancelling` | No | Cancel has been requested (or timeout elapsed); `compensate` callbacks are being sent. |
| `Closed` | Yes | All participants reported `Completed`; LRA is finished. |
| `Cancelled` | Yes | All participants reported `Compensated`; LRA is finished. |
| `FailedToClose` | Yes | One or more participants could not complete after all recovery attempts. |
| `FailedToCancel` | Yes | One or more participants could not compensate after all recovery attempts. |

### 11.2 Transitions

```
                         join / status
                         ┌──────────────────────────┐
                         │                           │
                         ▼        close()            │
                      Active ───────────────▶ Closing
                         │                        │ all complete
                         │ cancel() /             ▼
                         │ timeout       ┌─────Closed (terminal)
                         ▼               │
                    Cancelling           │ any FailedToComplete
                         │               └─────FailedToClose (terminal)
                         │ all compensate
                         ▼
                     Cancelled (terminal)
                         │
                         │ any FailedToCompensate
                         ▼
                   FailedToCancel (terminal)
```

**Additional timeout transition:**

```
Active + (currentTime > startTime + timeoutMs) ──▶ Cancelling
```

This transition is evaluated on every recovery scan cycle (§14.3).

### 11.3 Persistence-before-transition Rule

**Every state transition must be persisted to the state store and acknowledged by quorum before:**

1. The corresponding HTTP response is returned to the client.
2. Any participant callback for that transition is dispatched.

This rule is non-negotiable. Returning a success response before a successful quorum write is a conformance violation (P-02).

**If the state store write fails**, the coordinator must:
1. Revert the in-memory state to the pre-transition value.
2. Return HTTP 500 (for `StoreException`) or HTTP 503 (for `StoreUnavailableException`) to the client.
3. Not send any participant callback.

---

## 12. LRA Lifecycle — Distributed Operations

### 12.1 Start LRA

```
startLRA(timeout, parentId):
  record = newRecord(generateLraId(nodeId), Active, timeout, parentId)
  stateStore.write(key(record.lraId), serialise(record))  // quorum write
  inMemoryMap.put(record.lraId, record)
  return record.lraId                                       // HTTP 201
```

The LRA ID must be globally unique. An implementation may use UUIDs, UidHelper-style identifiers, or any other collision-free scheme, provided the node ID is embedded in the resulting URI path (§10.2).

If `parentId` is set, the coordinator must verify the parent LRA exists (in memory or in the state store) before creating the child. If the parent is not found, the coordinator must return HTTP 404.

### 12.2 Join LRA (Participant Registration)

```
joinLRA(lraId, participant):
  lra = getOrActivate(lraId)                          // see §10.1
  if lra.state != Active:
    return HTTP 412 (Precondition Failed)
  deduplicate(lra, participant)                        // §10.3
  lra.participants.add(participant)
  stateStore.write(key(lraId), serialise(lra))         // quorum write
  return HTTP 200
```

### 12.3 Close LRA

```
closeLRA(lraId):
  lra = getOrActivate(lraId)
  if lra.state not in {Active, Closing}:
    return HTTP 412 or HTTP 200 (idempotent if already Closed)

  acquired = stateStore.tryLock(lraId)
  if not acquired:
    return HTTP 503                                    // lock held by another node

  try:
    lra.state = Closing
    stateStore.write(key(lraId), serialise(lra))       // quorum write
    dispatchComplete(lra)                              // send to all participants
    lra.state = Closed (or FailedToClose)
    stateStore.write(key(lraId), serialise(lra))       // quorum write
    return HTTP 200
  finally:
    stateStore.releaseLock(lraId)
```

### 12.4 Cancel LRA

Analogous to `closeLRA` (§12.3) with:
- Target state `Cancelling` instead of `Closing`.
- Participant callbacks are `compensate` instead of `complete`.
- Terminal states are `Cancelled` and `FailedToCancel`.

### 12.5 Query LRA Status

```
getStatus(lraId):
  lra = getOrActivate(lraId)                          // §10.1
  return lra.state                                    // HTTP 200
```

The read used to activate must be linearisable (§7.2).

### 12.6 Nested LRA Handling

When closing or cancelling a parent LRA:

1. Load each child LRA record from the state store by its stored ID.
2. If the parent is closing: drive each child through `closeLRA`.
3. If the parent is cancelling: drive each child through `cancelLRA`.
4. Finalise the parent only after all children have reached a terminal state.

A child that has already reached a terminal state must be treated as already resolved and not driven again.

---

## 13. Leader Election and Recovery Authority

### 13.1 Purpose

The recovery leader is the single coordinator node authorised to run cluster-wide recovery scans. Normal LRA operations (start, join, close, cancel) may be handled by any node.

Concentrating recovery authority in a single elected node prevents concurrent recovery scans from sending duplicate participant callbacks.

### 13.2 Election Algorithm

**Requirements:**

- The algorithm must be deterministic: all nodes must compute the same leader given the same membership view.
- The algorithm must converge within one membership view change event.
- The algorithm must not require inter-node communication beyond the membership view itself.

**Reference algorithm (implementors may use any equivalent):**

> The recovery leader is the node with the lexicographically smallest node ID in the current membership view.

Any stable, deterministic algorithm that produces exactly one leader given any view is conformant.

### 13.3 Leader Transition Behaviour

When a node detects it has **become** the recovery leader (via membership view change):

1. Stop any running recovery scan from the previous term (if this node was already leader and the view changed).
2. Schedule an **immediate** recovery scan (zero delay), not a deferred one.
3. After the immediate scan, resume the configured periodic scan interval.

When a node detects it has **lost** recovery leadership:

1. Cease scheduling any further recovery scans.
2. Complete any in-progress recovery operation for LRAs already locked (the lock will be released normally; the new leader will handle unlocked LRAs on its first scan).

### 13.4 Leader Notification Interface

The Cluster Membership Service must call into the Coordinator Service via the following interface when the leadership view changes:

```
interface LeadershipListener:
  onBecameLeader()
  onLostLeadership()
```

The Coordinator Service subscribes to this interface. The Cluster Membership Service must not call `onBecameLeader` and `onLostLeadership` concurrently.

---

## 14. Recovery Protocol

### 14.1 Trigger Conditions

A recovery scan must be triggered by any of:

| Trigger | Description |
|---------|-------------|
| **Leadership acquired** | This node becomes recovery leader (§13.3). Scan starts immediately. |
| **Periodic timer** | Configurable interval (`lra.coordinator.recovery.scan.interval.seconds`, default 120 s). |
| **Startup (HA mode)** | If this node is the leader at startup, after step 4 of the startup sequence (§5.1). |

### 14.2 Recovery Scan Algorithm

```
recoveryScan():
  keys = stateStore.enumerate(LRA_KEY_PREFIX)         // snapshot of all LRA keys
  for key in keys:
    uid = extractUid(key)
    tryRecoverLRA(uid)
```

The scan must iterate a **snapshot** of the key set. Concurrent writes during the scan must not cause missed entries or `ConcurrentModificationException`.

### 14.3 Single LRA Recovery

```
tryRecoverLRA(uid):
  acquired = stateStore.tryLock(uid)
  if not acquired:
    return                                             // another node handles it

  try:
    record = stateStore.read(key(uid))
    if record == null:
      return                                           // deleted by another node

    // Evaluate timeout transition
    if record.state == Active and isTimedOut(record):
      record.state = Cancelling
      stateStore.write(key(uid), serialise(record))    // quorum write

    if isTerminal(record.state):
      return                                           // already done

    // Re-drive participant callbacks
    drivenToCompletion = driveParticipants(record)

    if drivenToCompletion:
      record.state = terminalState(record)
      stateStore.write(key(uid), serialise(record))    // quorum write
    else:
      record.recoveryAttempts += 1
      stateStore.write(key(uid), serialise(record))    // quorum write

      if record.recoveryAttempts >= MAX_RECOVERY_ATTEMPTS:
        moveToFailedArea(uid, record)

  finally:
    stateStore.releaseLock(uid)
```

### 14.4 Failed LRA Handling

When an LRA exceeds `lra.coordinator.recovery.max.attempts` (default 5):

1. Write the record to the failed area key: `failed/{uid}` (see Appendix B).
2. Remove the active area key: `{type}/{uid}`.
3. Set the state to `FailedToClose` or `FailedToCancel` as appropriate.
4. Emit a structured log event at ERROR level (§16.2).

The failed area is permanent. Entries in the failed area are never automatically removed. An operator must manually inspect and resolve them.

### 14.5 Recovery After Full Cluster Restart

After all coordinator nodes restart:

1. Each node rebuilds its in-memory state from the state store (§5.1, step 4).
2. The elected leader runs an immediate recovery scan.
3. All LRAs that were in non-terminal states before shutdown are recovered.

**Requirement:** No LRA record that was quorum-acknowledged before the shutdown must be lost after a full restart. This requires the state store to be persistent (§7.3).

### 14.6 Participant Status Query

Before sending a callback, the recovery scan may query the participant's `/status` endpoint to determine whether the callback was already received. If the participant reports `Completed` or `Compensated`, the coordinator must skip the callback and update the participant entry's status accordingly.

If the participant's status endpoint is not available, the coordinator must send the callback anyway (idempotency assumption, P-05).

---

## 15. Failure Scenarios

### 15.1 Coordinator Node Crash (Minority)

**Precondition:** One or two nodes crash in a three-node or five-node cluster.

**Required behaviour:**

1. The cluster membership view is updated within `lra.coordinator.heartbeat.timeout.ms`.
2. The remaining nodes form a majority and continue to accept read and write operations.
3. If the crashed node held recovery leadership, one of the surviving nodes is elected as the new leader within one view change event.
4. If the crashed node held any distributed locks, the locks expire within `lra.coordinator.lock.ttl.seconds` and the new leader's recovery scan acquires them.
5. All LRAs that were active at the time of the crash are recovered by the new leader within one recovery scan interval after the lock expiry.

No manual operator intervention must be required.

### 15.2 Graceful Node Shutdown

**Required behaviour:**

1. In-flight requests complete normally (up to the drain timeout).
2. All distributed locks held by the shutting-down node are released before disconnect.
3. The remaining nodes continue to handle all requests.
4. If the shutting-down node was the recovery leader, the new leader is elected and begins its first recovery scan immediately.

### 15.3 Network Partition (Split-Brain)

**Precondition:** The cluster is split into a majority partition and a minority partition.

**Required behaviour on the majority partition:**

- The majority partition continues to handle all LRA operations normally.
- Coordinator nodes in the majority partition see only majority nodes in their membership view.

**Required behaviour on the minority partition:**

- Coordinator nodes in the minority partition must **refuse write operations** (start, join, close, cancel, recovery writes) and return HTTP 503 to clients.
- Read operations (status query) may be served from the minority partition with a staleness warning if the implementation supports it. Otherwise, HTTP 503 must be returned for all operations.
- The minority partition must not issue participant callbacks.
- When the partition heals, the minority partition nodes rejoin the majority view and synchronise state from the store.

**Rationale:** Allowing minority partition writes risks split-brain: two partitions may produce conflicting state for the same LRA. Accepting temporary unavailability is preferable to accepting the risk of unresolvable conflict (P-01).

### 15.4 Distributed Store Unavailability

**Precondition:** The state store is unreachable from all coordinator nodes.

**Required behaviour:**

- All state-mutating operations (start, join, close, cancel) must return HTTP 503.
- The coordinator must not fail silently or accept requests that would result in non-durable state.
- The coordinator must periodically retry the store connection.
- When the store becomes available again, the coordinator must resume normal operation without restart.
- Any LRA that was `Closing` or `Cancelling` at the time of the outage must be recovered by the recovery leader's next scan after the store is restored.

### 15.5 Duplicate Participant Callbacks

Duplicate callbacks may occur in the following scenarios:

- The coordinator sent a callback, the participant acknowledged it, but the coordinator crashed before updating the LRA record.
- The coordinator sent a callback to two different participants and crashed after one acknowledged.
- The recovery scan re-sends a callback that was already delivered.

**Required behaviour:**

- The coordinator must not attempt to suppress all duplicates proactively; it must instead rely on participant idempotency (ASSUME-01/P-05).
- The recovery scan should query participant status endpoints before re-sending to reduce unnecessary duplicates (§14.6).
- After a successful participant response, the coordinator must update the participant's status in the LRA record before sending the next callback.

---

## 16. Observability Requirements

### 16.1 Metrics

An implementation should expose the following metrics (names are informative; the naming scheme is implementation-defined):

| Metric | Type | Description |
|--------|------|-------------|
| `lra.active.count` | Gauge | Number of LRAs currently in `Active` state. |
| `lra.closing.count` | Gauge | Number of LRAs currently in `Closing` state. |
| `lra.cancelling.count` | Gauge | Number of LRAs currently in `Cancelling` state. |
| `lra.completed.total` | Counter | Total LRAs that reached `Closed` state. |
| `lra.cancelled.total` | Counter | Total LRAs that reached `Cancelled` state. |
| `lra.failed.total` | Counter | Total LRAs that reached `FailedToClose` or `FailedToCancel`. |
| `lra.recovery.scan.duration` | Histogram | Duration of each recovery scan in milliseconds. |
| `lra.lock.acquire.total` | Counter | Total distributed lock acquisition attempts. |
| `lra.lock.contention.total` | Counter | Lock acquisitions that were rejected (lock held by another node). |
| `lra.store.write.duration` | Histogram | Latency of state store write operations. |
| `lra.store.unavailable.total` | Counter | Total operations that returned `StoreUnavailableException`. |

### 16.2 Structured Log Events

Implementations must emit structured log events at the levels specified below for every listed event. Log events must include at minimum the fields shown.

| Event | Level | Required Fields |
|-------|-------|----------------|
| LRA started | DEBUG | `lraId`, `nodeId`, `timeoutMs` |
| Participant registered | DEBUG | `lraId`, `participantId` |
| LRA state transition | INFO | `lraId`, `fromState`, `toState`, `nodeId` |
| Lock acquired | DEBUG | `lraId`, `nodeId`, `ttlSeconds` |
| Lock contention | DEBUG | `lraId`, `holdingNodeId` |
| Lock expired (recovery) | WARN | `lraId`, `nodeId` |
| Recovery scan started | INFO | `nodeId`, `lraCount` |
| Recovery scan completed | INFO | `nodeId`, `recoveredCount`, `failedCount`, `durationMs` |
| LRA moved to failed area | ERROR | `lraId`, `state`, `recoveryAttempts`, `reason` |
| Store write failed | ERROR | `lraId`, `operation`, `exceptionClass`, `message` |
| Node joined cluster | INFO | `nodeId`, `clusterSize` |
| Node left cluster | WARN | `nodeId`, `clusterSize` |
| Leadership acquired | INFO | `nodeId` |
| Leadership lost | INFO | `nodeId` |
| Minority partition detected | WARN | `nodeId`, `partitionSize`, `quorum` |

---

## 17. Configuration Reference

All configuration properties are optional unless stated as required. Default values must be applied when the property is absent.

| Property | Default | Unit | Description |
|----------|---------|------|-------------|
| `lra.coordinator.ha.enabled` | `false` | boolean | Enable distributed HA mode. When `false`, all clustering components are disabled. |
| `lra.coordinator.node.id` | *(derived)* | string | Override the node ID (§6.2). |
| `lra.coordinator.cluster.min.nodes` | `3` | int | Minimum number of nodes required for write operations in HA mode. |
| `lra.coordinator.heartbeat.timeout.ms` | `5000` | ms | Time after which a non-responding node is considered failed. |
| `lra.coordinator.lock.ttl.seconds` | `30` | seconds | Distributed lock TTL. Minimum value: 10. |
| `lra.coordinator.recovery.scan.interval.seconds` | `120` | seconds | Interval between periodic recovery scans on the recovery leader. |
| `lra.coordinator.recovery.max.attempts` | `5` | int | Maximum recovery attempts before moving an LRA to the failed area. |
| `lra.coordinator.startup.timeout.seconds` | `60` | seconds | Maximum time to wait for state store connection at startup. |
| `lra.coordinator.shutdown.drain.seconds` | `30` | seconds | Maximum time to drain in-flight requests on graceful shutdown. |
| `lra.coordinator.lra.timeout.default.ms` | `0` | ms | Default LRA timeout. 0 means no timeout. |

---

## 18. Conformance and Testing

### 18.1 Mandatory Conformance Requirements

An implementation is conformant to this specification if and only if it satisfies every MUST requirement in §§5–16. The following table lists the critical MUST requirements:

| Ref | Requirement | Test Strategy |
|-----|-------------|---------------|
| §5.1 | Startup: REST API must not accept requests before state store is connected and in-memory state rebuilt. | Integration: measure first-request latency vs. store-ready event. |
| §7.2 | Writes must not return before quorum acknowledgement. | Integration: kill quorum nodes mid-write; verify write is rejected, not silently lost. |
| §9.3 | Lock must be acquired with CAS. Concurrent `tryLock` calls must result in exactly one success. | Concurrency: fire N parallel `tryLock` calls from N nodes; verify exactly one returns true. |
| §9.4 | Lock must be released on all exit paths including exception paths. | Unit: inject store exceptions into close/cancel; verify lock is always released. |
| §11.3 | State must be persisted before callbacks are dispatched. | Integration: kill coordinator between persist and callback; verify recovery re-sends the callback exactly once. |
| §12.3 | `closeLRA` returns HTTP 503 if lock is not acquired. | Integration: hold lock manually; verify HTTP 503 is returned. |
| §13.3 | New leader runs an immediate scan on leadership acquisition. | Integration: crash leader; verify new leader scans within one lock TTL + scan trigger delay. |
| §14.3 | Recovery acquires the lock before modifying any LRA. | Integration: corrupt a record; verify recovery does not modify it without holding the lock. |
| §15.3 | Minority partition refuses write operations. | Integration: isolate minority partition via network policy; verify HTTP 503. |
| §15.4 | Store unavailability returns HTTP 503, not silent failure. | Integration: disconnect store; verify all write operations return HTTP 503. |

### 18.2 MicroProfile LRA Specification Compliance

A conformant implementation must pass the MicroProfile LRA TCK (Technology Compatibility Kit) version 2.0 or later in **both** single-node mode and HA mode.

The following MicroProfile LRA REST endpoints must be available and respond identically on every coordinator node:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/lra-coordinator` | POST | Start a new LRA |
| `/lra-coordinator` | GET | List active LRAs |
| `/lra-coordinator/{lraId}` | GET | Get LRA status |
| `/lra-coordinator/{lraId}/close` | PUT | Close an LRA |
| `/lra-coordinator/{lraId}/cancel` | PUT | Cancel an LRA |
| `/lra-coordinator/{lraId}` | DELETE | Remove a completed/cancelled LRA |
| `/lra-coordinator/{lraId}/time-limit` | PUT | Update LRA time limit |

See [Appendix A](#appendix-a--rest-api-surface) for HTTP status codes.

---

## Appendix A — REST API Surface

This appendix specifies the HTTP status codes that every conformant coordinator must return. These extend the MicroProfile LRA 2.0 specification with distributed-specific error codes.

### A.1 POST /lra-coordinator (Start)

| Condition | Status |
|-----------|--------|
| LRA created successfully | 201 Created |
| Parent LRA not found | 404 Not Found |
| State store quorum write failed | 500 Internal Server Error |
| State store unavailable | 503 Service Unavailable |

### A.2 PUT /lra-coordinator/{lraId}/close

| Condition | Status |
|-----------|--------|
| LRA closed successfully | 200 OK |
| LRA already closed (idempotent) | 200 OK |
| LRA not found | 404 Not Found |
| LRA not in a closeable state | 412 Precondition Failed |
| Distributed lock held by another node | 503 Service Unavailable |
| State store unavailable | 503 Service Unavailable |
| State store error | 500 Internal Server Error |

### A.3 PUT /lra-coordinator/{lraId}/cancel

| Condition | Status |
|-----------|--------|
| LRA cancelled successfully | 200 OK |
| LRA already cancelled (idempotent) | 200 OK |
| LRA not found | 404 Not Found |
| LRA not in a cancellable state | 412 Precondition Failed |
| Distributed lock held by another node | 503 Service Unavailable |
| State store unavailable | 503 Service Unavailable |
| State store error | 500 Internal Server Error |

### A.4 PUT /lra-coordinator/{lraId}/join (Participant Registration)

| Condition | Status |
|-----------|--------|
| Participant registered | 200 OK |
| LRA not found | 404 Not Found |
| LRA not active | 412 Precondition Failed |
| State store unavailable | 503 Service Unavailable |

---

## Appendix B — Key Namespace

All keys in the distributed state store follow a structured namespace to enable efficient prefix-based enumeration and to prevent collisions between different entry types.

| Namespace | Key Format | Description |
|-----------|-----------|-------------|
| LRA records | `{type}/{uid}` | Durable LRA state. `{type}` is the Narayana object store type path, e.g. `/StateManager/BasicAction/LongRunningAction`. `{uid}` is the file-string form of the LRA's unique ID. |
| Distributed locks | `lock/{uid}` | Active distributed lock entry. TTL-bearing. Never appears in `allObjUids` results. |
| Failed LRAs | `failed/{uid}` | Permanently failed LRA records. Excluded from normal recovery scans. |

**Prefix enumeration rules:**

- `allObjUids(type)` must return only keys matching the `{type}/` prefix, excluding `lock/` and `failed/` prefixes.
- `allTypes()` must exclude any key whose type segment starts with `lock` or `failed`.
- A conformant adaptor must snapshot the key set before iterating (§7.1, `enumerate`).

---

## Appendix C — Sequence Diagrams

### C.1 LRA Close — Cross-Node Happy Path

```
Client           Node B (REST)      Node B (Service)    State Store
  │                   │                    │                   │
  │── PUT /close ────▶│                    │                   │
  │                   │── closeLRA ───────▶│                   │
  │                   │                    │── read(lraId) ───▶│
  │                   │                    │◀── LRA record ────│
  │                   │                    │── tryLock(uid) ──▶│
  │                   │                    │◀── null (locked) ─│
  │                   │                    │── write(Closing) ▶│
  │                   │                    │◀── ack (quorum) ──│
  │                   │                    │                   │
  │             [dispatch complete to each participant]        │
  │                   │                    │                   │
  │                   │                    │── write(Closed) ─▶│
  │                   │                    │◀── ack (quorum) ──│
  │                   │                    │── releaseLock ───▶│
  │                   │                    │◀── ok ────────────│
  │◀── 200 OK ────────│◀── ok ─────────────│                   │
```

### C.2 Recovery Scan — New Leader After Crash

```
CMS (Node B)     Node B (Service)       State Store        Participants
     │                   │                   │                   │
     │── onBecameLeader ▶│                   │                   │
     │                   │── enumerate() ───▶│                   │
     │                   │◀── {uid1, uid2}───│                   │
     │                   │                   │                   │
     │       FOR EACH uid:                   │                   │
     │                   │── tryLock(uid) ──▶│                   │
     │                   │◀── null (locked) ─│                   │
     │                   │── read(uid) ─────▶│                   │
     │                   │◀── record ────────│                   │
     │                   │                   │                   │
     │            [evaluate state + timeout] │                   │
     │                   │── write(updated) ▶│                   │
     │                   │◀── ack (quorum) ──│                   │
     │                   │── compensate ─────────────────────────▶│
     │                   │◀── 200 OK ────────────────────────────│
     │                   │── write(Cancelled)▶│                  │
     │                   │◀── ack (quorum) ──│                   │
     │                   │── releaseLock ───▶│                   │
```

---

## Appendix D — Conformance Checklist

Use this checklist to evaluate whether an implementation satisfies this specification.

### D.1 Startup and Shutdown

- [ ] REST API does not accept requests until state store is connected (§5.1).
- [ ] In-memory state is rebuilt from the state store before requests are accepted (§5.1 step 4).
- [ ] Graceful shutdown releases all held locks before disconnect (§5.2 step 3).
- [ ] HA mode disabled: no distributed component is initialised (§5.3).

### D.2 Node Identity and Membership

- [ ] Node ID resolution order: system property → `HOSTNAME` env → PID fallback (§6.2).
- [ ] Node ID is non-empty and URL-safe (§6.2).
- [ ] Failed nodes are removed from the view within the heartbeat timeout (§6.1).
- [ ] All nodes compute the same leader for any given view (§13.2).

### D.3 State Store Adaptor

- [ ] `write` returns only after quorum acknowledgement (§7.2).
- [ ] `read` on state-transition paths is linearisable (§7.2).
- [ ] `putIfAbsent` is atomic (§7.2).
- [ ] `enumerate` returns a stable snapshot (§7.2).
- [ ] `StoreUnavailableException` and `StoreException` are distinct and correctly mapped to HTTP 503 / 500 (§7.4).

### D.4 Distributed Lock

- [ ] Lock acquire is CAS (§9.3).
- [ ] Concurrent `tryLock` from N nodes produces exactly one success (§9.3).
- [ ] Lock is released on all exit paths including exception paths (§9.4).
- [ ] Lock TTL is at least 10 seconds (§17, `lra.coordinator.lock.ttl.seconds`).
- [ ] Expired locks are recovered by next recovery scan (§9.5).

### D.5 LRA Lifecycle

- [ ] State is persisted before HTTP response is returned (§11.3).
- [ ] State is persisted before participant callbacks are dispatched (§11.3).
- [ ] Failed write reverts in-memory state and returns HTTP 500/503 (§11.3).
- [ ] Any node can activate and serve any LRA (§10.1).
- [ ] Participant re-registration is idempotent (§10.3).
- [ ] `closeLRA` returns HTTP 503 if lock is not acquired (§12.3).
- [ ] Nested LRA children are driven to terminal before parent is finalised (§12.6).

### D.6 Recovery

- [ ] New leader runs an immediate scan on leadership acquisition (§13.3).
- [ ] Recovery scan iterates a snapshot of the key set (§14.2).
- [ ] Timeout transition evaluated on every scan cycle (§14.3).
- [ ] Lock acquired before any LRA record is modified during recovery (§14.3).
- [ ] LRA exceeding max attempts moved to failed area (§14.4).
- [ ] All pre-shutdown LRAs recoverable after full cluster restart (§14.5).

### D.7 Failure Scenarios

- [ ] Minority partition refuses write operations with HTTP 503 (§15.3).
- [ ] Store unavailability returns HTTP 503 for all write operations (§15.4).
- [ ] No participant callbacks issued from minority partition (§15.3).

### D.8 Observability

- [ ] Structured log events emitted for all events listed in §16.2.
- [ ] Log events include the required fields at the specified levels.
- [ ] Metrics exposed for all items listed in §16.1 (informative).

### D.9 MicroProfile LRA Compliance

- [ ] MicroProfile LRA TCK 2.0+ passes in single-node mode.
- [ ] MicroProfile LRA TCK 2.0+ passes in HA mode.
- [ ] All required REST endpoints respond identically on every coordinator node (§18.2).

---

*End of Specification LRA-UNIV-001*

---

*Document maintained by the Narayana LRA team. Contributions and feedback via the project issue tracker.*
