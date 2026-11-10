# Distributed MicroProfile LRA Coordinator — Architecture

**Version:** 1.0-DRAFT  
**Status:** Draft  
**Spec reference:** [distributed-lra-coordinator-spec.md](distributed-lra-coordinator-spec.md) (LRA-DIST-COORD-001)

---

## Table of Contents

1. [Purpose](#1-purpose)
2. [System Context](#2-system-context)
3. [Major Components](#3-major-components)
4. [Component Responsibilities and Boundaries](#4-component-responsibilities-and-boundaries)
5. [Data Flow](#5-data-flow)
6. [Cross-Cutting Requirements](#6-cross-cutting-requirements)
7. [Key Architecture Decisions](#7-key-architecture-decisions)
8. [Assumptions and Decisions for Human Review](#8-assumptions-and-decisions-for-human-review)

---

## 1. Purpose

This document describes the architecture of the distributed MicroProfile LRA coordinator. It translates the observable requirements in the feature specification into a set of components, their responsibilities, their boundaries, and the data flows between them.

The architecture targets the **HA cluster deployment scenario** defined in spec §2: three or more coordinator nodes behind a load balancer, tolerating a minority node failure without service interruption. Single-node mode (HA disabled) is a degenerate case in which the clustering components are absent; the coordinator service layer and REST API layer are the same in both modes.

This document does not prescribe a specific distributed systems technology, does not define API contracts, does not define a database schema, and does not contain implementation tasks.

---

## 2. System Context

```
                        ┌─────────────────────────────────────────────────┐
                        │  Client microservices (participants & initiators) │
                        │  e.g. order-service, payment-service, ...         │
                        └─────────────────────┬───────────────────────────┘
                                              │  MicroProfile LRA REST API
                                              │  (start / join / close / cancel / status)
                        ┌─────────────────────▼───────────────────────────┐
                        │            Load Balancer / Ingress               │
                        │            (no session affinity — ASSUME-02)      │
                        └──────┬─────────────┬──────────────┬─────────────┘
                               │             │              │
                     ┌─────────▼──┐  ┌───────▼──┐  ┌───────▼──┐
                     │ Coord node │  │ Coord node│  │ Coord node│
                     │     A      │  │     B     │  │     C     │
                     └─────────┬──┘  └───────┬──┘  └───────┬──┘
                               │             │              │
                               └──────────────┬─────────────┘
                                              │
                       ┌──────────────────────▼──────────────────────────┐
                       │          Distributed State Store                  │
                       │  (replicated, strongly consistent, persistent)    │
                       └─────────────────────────────────────────────────┘
```

**External actors:**
- **Client microservices** — any service that starts, joins, closes, or cancels LRAs, or registers as a participant. Interact with the coordinator only over the MicroProfile LRA REST API.
- **Load balancer / ingress** — routes requests to coordinator nodes with no session affinity (ASSUME-02). Has no knowledge of LRA state.

**System boundary:**  
The distributed LRA coordinator consists of the coordinator nodes and the distributed state store. Both are internal to the system boundary. Participant services are external actors.

---

## 3. Major Components

Each coordinator node is composed of four internal components. The distributed state store is a shared infrastructure component.

```
 ┌─────────────────────────────────────────────────────────────────┐
 │                       Coordinator Node                          │
 │                                                                  │
 │  ┌──────────────────┐   ┌─────────────────────────────────────┐ │
 │  │  REST API Layer  │──▶│         Coordinator Service          │ │
 │  │                  │   │   (LRA lifecycle, in-memory state)   │ │
 │  └──────────────────┘   └────────────┬────────────────────────┘ │
 │                                      │                           │
 │                          ┌───────────▼──────────────────┐       │
 │                          │    State Store Adaptor        │       │
 │                          │  (read / write / lock / enum) │       │
 │                          └───────────┬──────────────────┘       │
 │                                      │                           │
 │  ┌───────────────────────────────────▼────────────────────────┐ │
 │  │              Cluster Membership Service                     │ │
 │  │   (view changes, failure detection, leader election)        │ │
 │  └────────────────────────────────────────────────────────────┘ │
 └─────────────────────────────────────────────────────────────────┘
                                      │
              ┌───────────────────────▼──────────────────────┐
              │          Distributed State Store              │
              │  • LRA records (key-value, serialised state)  │
              │  • Distributed lock entries (key-value + TTL) │
              │  • Failed-LRA area (distinct key prefix)      │
              └──────────────────────────────────────────────┘
```

| Component | Scope |
|-----------|-------|
| **REST API Layer** | Per node. External boundary of the coordinator. |
| **Coordinator Service** | Per node. LRA lifecycle and in-memory state management. |
| **State Store Adaptor** | Per node. Mediates all reads and writes to the distributed store. |
| **Cluster Membership Service** | Per node. Tracks cluster view; drives leader election. |
| **Distributed State Store** | Shared across all nodes. Authoritative durable state. |

---

## 4. Component Responsibilities and Boundaries

### 4.1 REST API Layer

**Responsibilities:**
- Expose the MicroProfile LRA coordinator REST endpoints on every node (spec §17.2, Appendix A.2).
- Accept requests for any LRA regardless of which node started it (spec §10.1 — stateless coordinator principle).
- Translate HTTP requests into calls on the Coordinator Service.
- Return HTTP error codes as prescribed by the spec (500 on store write failure, 503 on store unavailability or lock contention, 404 when an LRA is not found).

**Boundaries:**
- Does not make direct calls to the distributed state store.
- Does not participate in leader election or recovery decisions.
- Does not hold durable state itself.

---

### 4.2 Coordinator Service

**Responsibilities:**
- Maintain an in-memory map of active LRA records for fast local lookups.
- Implement the full LRA state machine: `Active → Closing/Cancelling → Closed/Cancelled/FailedToClose/FailedToCancel` (spec Appendix A.1).
- Before returning any response to the REST API layer, ensure the updated LRA record has been written to the distributed store (spec §12.1, §12.2, §9.4).
- Perform cross-node LRA lookup when an LRA is not found in local in-memory state: read from the state store and activate into memory before processing (spec §10.3).
- Acquire and release distributed locks for close and cancel operations (spec §12.3, §14). The distributed lock **must** be released on both success and failure exit paths of any close, cancel, or recovery operation.
- Drive participant callbacks (`complete`, `compensate`) after persisting the state transition.
- On becoming the recovery leader, immediately schedule and execute a recovery scan without waiting for the next scheduled interval (spec §11.3, §13.1).
- On each recovery scan cycle, detect Active LRAs whose timeout has elapsed and transition them to the Cancelling state before driving recovery (spec Appendix A.1 — timeout transition).
- On close or cancel of a parent LRA, load each child LRA record from the state store by identifier and drive each child to its correct terminal state before finalising the parent (spec §12.5).
- On node startup with HA enabled, read LRA records from the distributed store to rebuild local in-memory state before accepting requests. Defer cluster-wide recovery to the elected leader (spec §13.1 — startup trigger).
- Move LRAs that have exhausted recovery attempts to the failed-LRA store area (spec §13.4).

**Boundaries:**
- Reads and writes durable state exclusively through the State Store Adaptor; never directly.
- Does not implement leader election itself; it subscribes to leader-change events from the Cluster Membership Service.
- Does not issue participant callbacks before persisting the corresponding state transition.
- If a state store write fails, the in-memory state transition **must** be reverted and the caller **must** receive an error; the coordinator **must not** return success when a write has not been acknowledged (spec Appendix A.1, §12.1).

---

### 4.3 State Store Adaptor

**Responsibilities:**
- Translate coordinator state operations (write, read, remove, enumerate, lock, unlock) into operations on the distributed state store (spec §9.3).
- Enforce synchronous quorum writes: a write call returns only after quorum acknowledgement (spec §9.4, NFR-03).
- Implement the distributed lock protocol: atomic compare-and-swap acquire with TTL; conditional-remove release (spec §14.2).
- Serve the full-cluster enumerate operation used by the recovery scan; the result must include LRA records written by any node (spec §9.3 — enumerate, C-12).
- Surface store unavailability to the Coordinator Service as a distinct error so the service can return HTTP 503 (spec §15.4).

**Boundaries:**
- Does not contain LRA business logic. It is a pure I/O adaptor.
- Does not make HTTP calls to participants.
- Does not participate in leader election.
- Is the only component that communicates with the distributed state store.
- Must remain replaceable: any conformant distributed store that satisfies the consistency and durability requirements (spec §9.4, §9.5) can be plugged in by swapping this component.

---

### 4.4 Cluster Membership Service

**Responsibilities:**
- Maintain a live view of all coordinator nodes in the cluster.
- Detect node failures and graceful departures via a configurable heartbeat/timeout mechanism (spec §8.2).
- Run a deterministic leader election algorithm that produces exactly one recovery leader given any membership view (spec §11.2).
- Notify the Coordinator Service of leader transitions (`onBecameCoordinator`, `onLostCoordinator`) so that recovery scans are started or stopped promptly (spec §11.3).
- Provide the Coordinator Service with the current membership view for quorum checks.

**Boundaries:**
- Does not modify LRA records.
- Does not communicate with the distributed state store directly.
- Does not communicate with participant services.
- Must be replaceable: the Coordinator Service depends on a stable notification interface (`onBecameCoordinator` / `onLostCoordinator`), not on a specific clustering library.

---

### 4.5 Distributed State Store

**Responsibilities:**
- Store LRA records and distributed lock entries in a key-value model (spec §9.2).
- Replicate all writes to a quorum of nodes before acknowledging (spec §8.3, NFR-03).
- Provide linearisable write operations and linearisable reads on all paths that drive LRA state transitions (spec §9.4).
- Persist state across a full cluster restart; a purely in-memory store must provide a durable fallback (spec §9.5, C-13).
- Refuse write operations from coordinator nodes in a minority partition (spec §9.4, §15.3, C-08).
- Support TTL-bearing lock entries for distributed lock expiry (spec §14.2).

**Boundaries:**
- Shared infrastructure, not owned by a single coordinator node.
- The technology choice (embedded replicated store or external store) is deferred to implementation, subject to the consistency and durability requirements above (spec §1 — technology-neutral).

---

## 5. Data Flow

### 5.1 Normal Path: LRA Start

```
Client
  │─── POST /lra-coordinator ───────────────────▶ REST API Layer (any node)
                                                        │
                                                        ▼
                                               Coordinator Service
                                               1. Create LRA record (in memory)
                                                        │
                                                        ▼
                                               State Store Adaptor
                                               2. write(lra-id, record)
                                                        │
                                                        ▼
                                               Distributed State Store
                                               3. Replicate to quorum
                                               4. Acknowledge
                                                        │
                                                        ▼
                                               Coordinator Service
                                               5. Receive acknowledgement
                                                        │
                                                        ▼
                                               REST API Layer
  Client ◀─── 201 Created ────────────────────────────────
```

*Spec references: §12.1, §9.4, NFR-03*

---

### 5.2 Normal Path: Close / Cancel (cross-node)

The LRA was started on node A. The close request arrives at node B.

```
Client
  │─── PUT /lra-coordinator/{lra-id}/close ───▶ REST API Layer (node B)
                                                        │
                                                        ▼
                                               Coordinator Service (node B)
                                               1. LRA not in local memory
                                               2. Delegate to State Store Adaptor
                                                        │
                                                        ▼
                                               State Store Adaptor
                                               3. try-lock(lra-id)  [CAS, TTL]
                                               4. read(lra-id)
                                                        │
                                                        ▼
                                               Coordinator Service (node B)
                                               5. Activate LRA in local memory
                                               6. Transition: Active → Closing
                                                        │
                                                        ▼
                                               State Store Adaptor
                                               7. write(lra-id, Closing-state)
                                                        │
                                                        ▼
                                               Coordinator Service (node B)
                                               8. Send "complete" to each participant
                                               9. On all participants terminal:
                                                  Transition: Closing → Closed
                                                        │
                                                        ▼
                                               State Store Adaptor
                                               10. write(lra-id, Closed-state)
                                               11. release-lock(lra-id)
                                                        │
                                                        ▼
                                               REST API Layer
  Client ◀─── 200 OK ─────────────────────────────────────
```

*Spec references: §10.3, §12.3, §14.2*

---

### 5.3 Recovery Path: Leader-Driven Recovery Scan

```
Cluster Membership Service
  │─── onBecameCoordinator ────────────────────▶ Coordinator Service
                                                        │
                                                        ▼
                                               Coordinator Service
                                               1. Schedule immediate recovery scan
                                                        │
                                               FOR EACH lra-id IN store:
                                                        │
                                                        ▼
                                               State Store Adaptor
                                               2. try-lock(lra-id)
                                                  [if fails → skip, continue]
                                               3. read(lra-id)
                                                        │
                                                        ▼
                                               Coordinator Service
                                               4. If terminal → release-lock, skip
                                               5. Query participant /status endpoints
                                               6. Send appropriate callback
                                                        │
                                                        ▼
                                               State Store Adaptor
                                               7. write(lra-id, updated-state)
                                               8. release-lock(lra-id)
                                                        │
                                               [if max attempts exceeded]
                                                        │
                                                        ▼
                                               State Store Adaptor
                                               9. write(failed-lra-id, FailedTo* state)
                                               10. remove(active-lra-id)
```

*Spec references: §11.3, §13.1, §13.2, §13.3, §13.4*

---

### 5.4 Leader Crash and Re-election

```
Cluster Membership Service (node B)
                                     [node A leaves view]
  │─── onBecameCoordinator ────────────────────▶ Coordinator Service (node B)
                                                        │
                                                        ▼
                                               Coordinator Service (node B)
                                               1. Trigger immediate recovery scan
                                               2. For any LRA locked by crashed node A:
                                                  - Lock TTL expires (≤ configured TTL)
                                                  - try-lock succeeds on node B
                                                  - Resume recovery
```

*Spec references: §11.2, §11.3, §13.1, §14.2 (TTL), AC-13.1*

---

## 6. Cross-Cutting Requirements

These requirements apply across all components and flows. Each is derived directly from the feature specification.

### 6.1 Write-before-respond

Every component that modifies LRA state **must** ensure the state store write completes before returning a success response to its caller. This applies to start, join, close, cancel, and each recovery state transition.

If the state store write fails, the coordinator **must not** return success. The in-memory state **must** be reverted to the pre-transition state and the client **must** receive HTTP 500 Internal Server Error. An unacknowledged write is treated as if it never happened.

*Spec: §12.1, §12.2, §12.3, §13.2 (rule 3), Appendix A.1, C-02*

### 6.2 Quorum before acknowledge

The State Store Adaptor must not return from a write call until the store has confirmed quorum replication. No component may treat a write as durable until this confirmation is received.

*Spec: §8.3, §9.4, NFR-03, C-03*

### 6.3 Linearisable reads on state-transition paths

Reads used to drive LRA state transitions (close, cancel, recovery) must return the latest committed state. Cached or stale reads are not acceptable on these paths.

*Spec: §9.4*

### 6.4 No direct store access outside the adaptor

The Coordinator Service and the REST API Layer must not access the distributed state store except through the State Store Adaptor. This keeps the consistency contract in one place and allows the store technology to be replaced.

*Spec: §9 (all operations via adaptor), architecture decision AD-01*

### 6.5 HA mode is strictly additive

When `lra.coordinator.ha.enabled=false`, the Cluster Membership Service and distributed State Store Adaptor must not be initialised. The coordinator must function as a single-node implementation using only its local persistent store. There must be no performance cost, startup cost, or external dependency added by the presence of HA components on the classpath when HA is disabled.

*Spec: G-06, NFR-04, C-09*

### 6.6 Durability across full cluster restart

The distributed state store must survive a full restart of all coordinator nodes. All in-flight LRA records must be recoverable by the newly elected recovery leader.

*Spec: §9.5, §13.5, C-13*

### 6.7 MicroProfile LRA spec compliance must not be broken

No change to the coordinator's cluster membership, routing, or recovery behaviour may alter the MicroProfile LRA REST API surface, LRA state machine, or participant callback semantics observable by clients.

*Spec: G-04, §17.2, C-10*

---

## 7. Key Architecture Decisions

### AD-01 — Stateless coordinator: any node serves any LRA

**Decision:** No session affinity is required between an LRA and a coordinator node. Any node may serve any request for any LRA by loading the record from the distributed state store on demand.

**Rationale:** Satisfies G-01 (eliminate single point of failure) and G-02 (no affinity requirement). Allows a load balancer to route freely across nodes, simplifying operations and enabling rolling updates without draining connections. Eliminates the need for inter-node HTTP routing between coordinator nodes.

**Trade-off:** Every request for an LRA not in local memory requires a state store read (cross-node lookup). NFR-01 (p99 ≤ 200 ms) bounds the acceptable cost of this read.

**Spec references:** §10.1, §10.3, G-01, G-02, NFR-01

---

### AD-02 — Recovery is single-leader, normal traffic is not

**Decision:** Only the elected recovery leader runs periodic recovery scans. Normal LRA start, join, close, and cancel are handled by any node.

**Rationale:** Recovery scans enumerate all non-terminal LRAs and may send duplicate participant callbacks if run concurrently on multiple nodes. Serialising recovery through a single leader eliminates this risk without restricting normal-path throughput. Participants are assumed idempotent (ASSUME-01) so occasional duplicates are tolerable, but avoidable duplication is preferable.

**Trade-off:** The recovery leader is a bottleneck for recovery throughput. For the MVP cluster size (3–5 nodes), this is acceptable. If recovery throughput becomes a bottleneck at larger scales, partitioned recovery (each node recovers LRAs it owns) would require a different design.

**Spec references:** §11.1, §11.2, G-03, ASSUME-01

---

### AD-03 — Distributed locks stored co-located with LRA records

**Decision:** Distributed lock entries share the same key namespace as LRA records in the state store, using a distinct `lock/` prefix. The lock acquire operation is an atomic compare-and-swap within the same store.

**Rationale:** Using the state store as the lock backend avoids introducing a separate synchronisation service. The state store already provides linearisable writes, which is the only property required for a correct distributed lock. Co-location means that a lock and the record it protects are always managed by the same store nodes, with no possibility of a split-brain between the lock and the data.

**Trade-off:** Lock entries consume state store capacity. With a max of 10,000 concurrent LRAs (a reasonable upper bound for the MVP), this is negligible. Lock TTL prevents dead entries from accumulating after a node crash.

**Spec references:** §9.2, §14.2, C-07

---

### AD-04 — State written to store before callbacks are sent

**Decision:** Every LRA state transition (Active → Closing, Closing → Closed, etc.) is persisted to the distributed store before the coordinator sends the first participant callback for that transition.

**Rationale:** If the coordinator crashes between persisting and sending, the recovery leader will re-send the callback from the persisted state — relying on participant idempotency (ASSUME-01). If callbacks were sent before persisting, a crash would leave the store in a state inconsistent with what participants have already received, making correct recovery impossible without additional coordination.

**Trade-off:** This adds one store write on the critical path of every close/cancel. The latency is bounded by the quorum acknowledgement time, which is covered by NFR-01 (cross-node latency) and NFR-03 (synchronous quorum write).

**Spec references:** §12.3, Appendix A.1, G-03, ASSUME-01

---

### AD-05 — HA module is opt-in and additive

**Decision:** The clustering components (Cluster Membership Service, distributed State Store Adaptor) are activated only when `lra.coordinator.ha.enabled=true`. When disabled, the coordinator operates on its local persistent store with no external dependencies.

**Rationale:** Satisfies G-06: single-node deployments must continue to work unchanged. Allows adopters to upgrade to HA without a flag day. Keeps the core coordinator decoupled from any specific clustering library.

**Trade-off:** Two distinct code paths must be tested: HA mode and non-HA mode. The MicroProfile LRA TCK must pass in both.

**Spec references:** G-06, NFR-04, C-09, §16 (`lra.coordinator.ha.enabled`)

---

### AD-06 — Minority partition is read-only

**Decision:** Under a network partition, coordinator nodes in the minority partition (fewer than quorum) must refuse write operations and return HTTP 503 to clients.

**Rationale:** Allowing minority nodes to accept writes risks split-brain: two partitions could produce conflicting state for the same LRA, which cannot be safely merged. When the partition heals, the majority-partition state wins (spec §15.3). Refusing minority writes is the only way to guarantee this policy is never violated.

**Trade-off:** Clients targeting the minority partition experience degraded availability (read-only) until the partition heals. This is an explicit trade-off in favour of consistency (CP over AP). It is the correct choice for a transaction coordinator, where incorrect state is worse than temporary unavailability.

**Spec references:** §15.3, §9.4, G-08, C-08

---

## 8. Assumptions and Decisions for Human Review

The following items affect the architecture but have not been fully resolved. Each requires explicit human decision before the implementation can be considered complete.

| ID | Item | Impact | Owner | Status |
|----|------|--------|-------|--------|
| AR-01 | **State store technology selection.** The architecture is technology-neutral by design (AD-03). The chosen technology must satisfy the consistency (linearisable writes and reads, spec §9.4), durability (persistent across full restart, spec §9.5), and lock TTL (spec §14.2) requirements. An embedded replicated store (e.g. Infinispan REPL_SYNC with DENY_READ_WRITES) and an external consensus store (e.g. etcd) are both viable. The choice has deployment, operational, and dependency footprint implications. | Determines the concrete implementation of the State Store Adaptor. | Architect | Open — see spec OQ-02 |
| AR-02 | **Cluster membership technology selection.** The architecture requires a Cluster Membership Service that provides reliable failure detection and deterministic leader election (spec §11.2). The technology is not prescribed. JGroups view-based election (already used by the Infinispan implementation on this branch) and a Raft-based consensus service are the most natural candidates. If the state store uses an embedded replicated store with JGroups transport, the same JGroups channel can serve both membership and transport, simplifying the deployment. | Determines the concrete implementation of the Cluster Membership Service. | Architect | Open |
| AR-03 | **Recovery time SLA (spec OQ-01).** The architecture supports configurable recovery scan interval and leader election timeout (spec §16). No hard end-to-end recovery SLA is imposed. A production SLA (from node failure to LRA resolution) should be agreed and added as a NFR before certification. Until agreed, the default configuration (election timeout 10 s, scan interval 120 s) is the effective bound. | If a tighter SLA is required, the default scan interval may need to be reduced, with corresponding increased state store read load. | Product owner | Open |
| AR-04 | **State store replication factor vs. coordinator cluster size (spec OQ-02).** If the state store's replication factor is lower than the coordinator cluster size, a store node failure can cause data loss even if coordinator nodes survive. The architecture does not mandate equal replication. This needs an explicit policy for production deployments. | Affects durability guarantee (NFR-03). | Architect | Open |
| AR-05 | **Lock TTL calibration.** The default lock TTL is 30 seconds (spec §14.2). This must be at least as long as the maximum expected participant callback round-trip time. In deployments with slow or unreliable participant services, a 30-second TTL may be too short, causing the lock to expire while a callback is in-flight. The TTL is configurable (spec §16), but the correct default for the target environment should be validated during integration testing. | If TTL is too short, a live node can lose its lock to recovery, causing duplicate callbacks. | Implementor | Needs validation during integration testing |

---

*End of Architecture*

---

*Document maintained by the Narayana LRA team. Contributions and feedback via the project issue tracker.*
