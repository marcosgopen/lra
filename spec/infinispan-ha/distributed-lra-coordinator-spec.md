# Distributed MicroProfile LRA Coordinator — Specification

**Version:** 1.0-DRAFT  
**Status:** Draft  
**Spec ID:** LRA-DIST-COORD-001  
**Applicable MicroProfile LRA version:** 2.0+

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Target Users and Scenarios](#2-target-users-and-scenarios)
3. [Assumptions](#3-assumptions)
4. [Open Questions](#4-open-questions)
5. [Motivation and Goals](#5-motivation-and-goals)
6. [Non-Functional Requirements](#6-non-functional-requirements)
7. [Terminology](#7-terminology)
8. [Node Identity and Membership](#8-node-identity-and-membership)
9. [Distributed State Store](#9-distributed-state-store)
10. [LRA Routing and Ownership](#10-lra-routing-and-ownership)
11. [Leader Election and Recovery Authority](#11-leader-election-and-recovery-authority)
12. [LRA Lifecycle Semantics in a Distributed Cluster](#12-lra-lifecycle-semantics-in-a-distributed-cluster)
13. [Recovery Protocol](#13-recovery-protocol)
14. [Distributed Locking](#14-distributed-locking)
15. [Failure Scenarios and Required Behaviour](#15-failure-scenarios-and-required-behaviour)
16. [Configuration Reference](#16-configuration-reference)
17. [Conformance Requirements](#17-conformance-requirements)
18. [Appendix A — MicroProfile LRA Spec Compliance Notes](#appendix-a--microprofile-lra-spec-compliance-notes)
19. [Appendix B — Worked Example with Three Nodes (Informative)](#appendix-b--worked-example-with-three-nodes-informative)

---

## 1. Introduction

This document specifies the requirements for a **distributed, multi-node MicroProfile LRA coordinator**. It is intended as a requirements reference for any team building or replacing the coordinator tier in a system that runs the [MicroProfile Long Running Actions (LRA)](https://github.com/eclipse/microprofile-lra) specification.

The specification does **not** prescribe a specific distributed systems technology (Infinispan, etcd, Redis, Zookeeper, CockroachDB, etc.) nor a specific deployment platform. It defines the required observable properties, contracts, and failure semantics that any compliant implementation must satisfy.

### Scope

- Coordinator cluster topology and membership management.
- Distributed state storage requirements for LRA records.
- LRA ownership, routing, and handover behaviour.
- Leader election scoped to recovery operations.
- Recovery protocol including crash-restart and split-brain behaviour.
- Conformance requirements relative to the MicroProfile LRA specification.

### Out of scope

- Client-side LRA SDK or annotation processing.
- MicroProfile LRA participant implementation details.
- Specific transport, clustering technology, or deployment platform selection.
- Performance benchmarking methodology.
- Internal class hierarchies, module structure, or code organisation.

---

## 2. Target Users and Scenarios

### Primary users

- **Backend engineers** using Quarkus or JBoss EAP who need saga / long-running transaction coordination with guaranteed availability across rolling updates and node failures.
- **Platform and operations engineers** deploying the coordinator on Kubernetes or OpenShift and responsible for availability SLAs.
- **Narayana / Red Hat maintainers** implementing or extending the coordinator.

### Primary deployment scenarios

| Scenario | Description |
|----------|-------------|
| **HA cluster (target)** | Three or more coordinator nodes deployed as a `StatefulSet` behind a load balancer. The distributed state store is replicated across all nodes. A minority of nodes may fail without service interruption. |
| **Single-node (fallback)** | One coordinator node with a local persistent store. No clustering. Must continue to work without any distributed store dependency when HA is disabled. Please note that multiple nodes of this kind can be deployed, but they will not share the object store, so the LRAs are owned and managed by the node which created them (single point if failure)|

### Non-target scenarios (for this version)

- Geo-distributed multi-region deployments with cross-region replication.
- Deployments with more than twenty coordinator nodes.

---

## 3. Assumptions

The following are taken as given by this specification. They are not requirements on the distributed coordinator itself.

| ID | Assumption |
|----|-----------|
| ASSUME-01 | Participants implementing the MicroProfile LRA specification are idempotent: receiving the same `complete` or `compensate` callback more than once produces the same outcome as receiving it once. This is required by the MicroProfile LRA specification (§6.3) and is a precondition for safe recovery. |
| ASSUME-02 | The load balancer or ingress in front of the coordinator cluster has no session affinity by default. Any request may be routed to any coordinator node. |
| ASSUME-03 | The distributed state store is accessible from all coordinator nodes over the internal network. Store availability is a deployment-level concern; this spec defines coordinator behaviour when the store is temporarily unavailable (§15.4) but does not specify store infrastructure. |
| ASSUME-04 | The minimum supported cluster size for HA mode is three nodes. A two-node cluster is not a supported HA configuration because a single node failure eliminates quorum. |
| ASSUME-05 | JDK 17 or later. |
| ASSUME-06 | Coordinator nodes share a common wall-clock time within a reasonable skew (≤ 1 second). Time skew beyond this may cause lock TTL or LRA timeout inaccuracies. |

---

## 4. Open Questions

| ID | Question | Owner | Status |
|----|----------|-------|--------|
| OQ-01 | What is the maximum acceptable end-to-end recovery time after a full cluster restart? The current spec allows this to be configured; no hard SLA is imposed. A production SLA should be agreed and added as a NFR before the implementation is certified. | Product owner | Open |
| OQ-02 | Should the spec mandate a minimum replication factor for the distributed state store independent of the cluster size? For example, if a five-node coordinator cluster uses a state store with replication factor 2, one store node failure can cause data loss. | Architect | Open |

---

## 5. Motivation and Goals

### 5.1 Problems with a Single-Instance Coordinator

A single-instance LRA coordinator is a **single point of failure** for every LRA in the system. If the coordinator crashes:

- Active LRAs cannot be completed, compensated, or closed until the coordinator restarts.
- Any participant that receives a timeout may close or compensate based on its own policy, which may contradict what the coordinator would have decided.
- Recovery is blocked entirely until the coordinator process and its persistent store are back online.

In production microservice deployments on Kubernetes or OpenShift, rolling updates and pod evictions make single-node coordinator availability insufficient for most SLA requirements.

### 5.2 Goals

| ID | Goal | Priority |
|----|------|----------|
| G-01 | Eliminate the coordinator as a single point of failure. | MUST |
| G-02 | Allow any cluster node to handle any LRA request without requiring affinity to the originating node. | MUST |
| G-03 | Guarantee that every LRA reaches a terminal state (Closed, Cancelled, FailedToClose, FailedToCancel) even when one or more coordinator nodes crash. | MUST |
| G-04 | Maintain full MicroProfile LRA specification compliance; no breaking changes to the public LRA API or participant callback contracts. | MUST |
| G-05 | Support a minimum cluster size of three nodes to provide quorum. | MUST |
| G-06 | Remain additive: a single-node deployment without clustering must continue to work unchanged when HA is disabled. | MUST |
| G-07 | Tolerate a minority node failure (fewer than half the nodes fail) without data loss. | MUST |
| G-08 | Provide deterministic behaviour when a network partition heals. | MUST |

---

## 6. Non-Functional Requirements

| ID | Requirement | Threshold | Notes |
|----|-------------|-----------|-------|
| NFR-01 | **Cross-node LRA lookup latency** — the additional time to serve a request for an LRA not in the local in-memory map (i.e. a state store read is required). | p99 ≤ 200 ms on a three-node cluster under normal operating conditions. | Measured from receipt of request to start of business logic. Does not include participant callback time. |
| NFR-02 | **Recovery leader election time** — the time from loss of the current recovery leader to the new leader beginning its first recovery scan. | Configurable; default ≤ 10 seconds. No hard upper bound is imposed by this spec. | See `lra.coordinator.leader.election.timeout` in §16. |
| NFR-03 | **State store write durability** — a write acknowledged to the coordinator MUST have been replicated to a quorum of store nodes. | Synchronous quorum write; no acknowledged writes may be lost on minority node failure. | |
| NFR-04 | **Single-node mode startup overhead** — starting the coordinator with HA disabled must not incur any distributed store initialisation overhead. | No distributed store connections are opened when `lra.coordinator.ha.enabled=false`. | |

---

## 7. Terminology

| Term | Definition |
|------|-----------|
| **LRA** | Long Running Action — a logical unit of work coordinated across multiple microservices, as defined by the MicroProfile LRA specification. |
| **Coordinator node** | A process that implements the MicroProfile LRA coordinator REST API and participates in the coordinator cluster. |
| **Cluster** | The set of all active coordinator nodes sharing a common distributed state store and cluster membership view. |
| **Quorum** | The minimum number of nodes that must agree before a write is considered durable. For a cluster of *n* nodes this is `⌊n/2⌋ + 1`. |
| **LRA record** | The durable serialised state of one LRA (status, participants, timestamps, parent/child links), stored in the distributed state store. |
| **Recovery leader** | The single node in the cluster that is authorised to drive periodic recovery scans. Only one recovery leader may exist at any time. |
| **Recovery scan** | A periodic sweep of the state store that finds LRAs in a non-terminal state and drives them to completion or cancellation. |
| **Distributed lock** | A cluster-wide, time-bounded, exclusive lock on a single LRA identifier, used to prevent concurrent modification during recovery. |
| **State store adaptor** | The component that translates coordinator state operations into reads and writes on the distributed state store. |
| **Participant** | A microservice that has registered a set of URLs (complete, compensate, status, forget, after) with the coordinator as part of an LRA. |
| **Node ID** | A string that uniquely identifies a coordinator node within a cluster. Must be stable across restarts of the same logical node. |
| **Terminal state** | An LRA state from which no further transitions are possible: Closed, Cancelled, FailedToClose, or FailedToCancel. |

---

## 8. Node Identity and Membership

### 8.1 Node ID Requirements

Every coordinator node **MUST** have a node ID that:

1. Is unique within the cluster at any point in time.
2. Is stable across a graceful restart of the same logical node (e.g. pod name in a Kubernetes `StatefulSet`).
3. Is a non-empty string, maximum 255 characters, containing only printable ASCII characters.

In Kubernetes environments, the node ID **SHOULD** be derived from the pod name so that it is both unique and stable.

A randomly generated ID that changes on every restart **MUST NOT** be used in production because it breaks distributed lock ownership attribution.

### 8.2 Cluster Membership View

The implementation **MUST** maintain a consistent view of the set of alive coordinator nodes. This view is used to:

1. Determine quorum for write operations.
2. Drive leader election when the current recovery leader leaves the cluster.

Membership detection **MUST** be based on a reliable failure-detection mechanism (heartbeat with configurable timeout, or a consensus protocol).

### 8.3 Quorum

Writes to the distributed state store **MUST NOT** be acknowledged to callers unless they have been replicated to a quorum of nodes (`⌊n/2⌋ + 1`). This guarantees that a durable LRA record survives the loss of any minority partition.

For a three-node cluster, quorum is two nodes. For a five-node cluster, quorum is three nodes.

Implementations **MUST** document the quorum formula they use and the minimum cluster size required to tolerate one node failure (minimum: three nodes per ASSUME-04).

### 8.4 Acceptance Criteria

**AC-8.1 — Unique node ID**  
Given a cluster of three coordinator nodes started with distinct node IDs,  
when each node joins the cluster,  
then the membership view on every node lists exactly three distinct node IDs.

**AC-8.2 — Stable node ID across restart**  
Given coordinator node "coord-1" with node ID "coord-1" is stopped and restarted,  
when the node rejoins the cluster,  
then its node ID in the membership view is still "coord-1", not a new random value.

**AC-8.3 — Quorum enforcement**  
Given a three-node cluster where two nodes are stopped (below quorum),  
when a client sends an LRA start request to the surviving node,  
then the coordinator returns HTTP 503 Service Unavailable without writing any LRA state.

---

## 9. Distributed State Store

### 9.1 Purpose

The distributed state store is the authoritative, durable source of truth for all LRA records. Every coordinator node reads from and writes to this shared store.

### 9.2 Required Data Model

The store **MUST** support a key–value model with the following entry types:

**LRA record entry**
- Key: a compound identifier consisting of the LRA type path and the LRA unique identifier.
- Value: opaque byte array representing the serialised LRA state (status, participants, timestamps, parent/child relationships).

**Distributed lock entry**
- Key: a lock namespace prefix combined with the LRA unique identifier.
- Value: the node ID of the lock holder (UTF-8 string).
- TTL: MUST be carried on each lock entry; MUST be configurable; MUST be ≥ 10 seconds.

Lock entries **MUST** be stored in the same namespace as LRA records to enable atomic compare-and-swap operations.

### 9.3 Required Operations

The state store adaptor **MUST** support the following operations:

| Operation | Required Semantics |
|-----------|--------------------|
| **write** (identifier, state) | Atomically write or overwrite an LRA record. MUST be synchronous: returns only after quorum acknowledgement. |
| **read** (identifier) | Return the latest committed value, or absent if not found. |
| **remove** (identifier) | Remove an LRA record. Returns a success indicator if the record existed. |
| **enumerate** (type) | Return the set of all LRA identifiers stored under the given type prefix, including those written by other nodes. |
| **exists** (identifier) | Return whether a record is present. |
| **try-lock** (identifier) | Atomically set a lock entry for the identifier to this node's ID, only if no lock entry is currently present. Returns true on success, false if another node holds the lock. MUST attach a TTL. |
| **release-lock** (identifier) | Remove the lock entry only if its current value equals this node's ID. |

### 9.4 Consistency Requirements

- All writes **MUST** be strongly consistent (linearisable) across nodes.
- Reads used to drive LRA state transitions (recovery, close, cancel) **MUST** return the latest committed state — stale reads are not acceptable for these paths.
- Under a network partition, the minority partition **MUST** refuse all write operations. It **MUST** return HTTP 503 to any coordinator trying to perform a write. Read-only status queries are permitted.

### 9.5 Durability Across Full Cluster Restart

The distributed state store **MUST** be persistent across a full coordinator cluster restart. An implementation that uses a purely in-memory store **MUST** also provide a durable persistent fallback so that LRA records are not lost when all coordinator nodes are simultaneously stopped.

### 9.6 Acceptance Criteria

**AC-9.1 — Write before response**  
Given a coordinator node receives an LRA start request,  
when it returns HTTP 201 Created to the client,  
then the LRA record is readable from the distributed store on every other coordinator node.

**AC-9.2 — Cross-node read**  
Given LRA "lra-001" was started on coordinator node A and stored in the distributed store,  
when coordinator node B receives a status request for "lra-001" and has no local in-memory entry for it,  
then node B returns the correct LRA status by reading the record from the distributed store.

**AC-9.3 — Quorum write failure**  
Given the distributed store is unavailable (all store nodes unreachable),  
when a coordinator node attempts to start a new LRA,  
then the coordinator returns HTTP 503 Service Unavailable and no LRA state is created.

**AC-9.4 — Lock exclusivity**  
Given two coordinator nodes simultaneously attempt to acquire a distributed lock on LRA "lra-001",  
when both try-lock operations are issued concurrently,  
then exactly one succeeds and the other returns false.

**AC-9.5 — Lock TTL expiry**  
Given coordinator node A holds the distributed lock on LRA "lra-001" and then crashes before releasing it,  
when the configured lock TTL elapses,  
then coordinator node B is able to successfully acquire the lock on "lra-001".

---

## 10. LRA Routing and Ownership

### 10.1 Stateless Coordinator Principle

Every coordinator node **MUST** be capable of processing any request for any LRA, regardless of which node originally started the LRA. There is no affinity between an LRA and its originating node at the API layer.

### 10.2 Node ID Embedding in LRA URIs

The LRA URI uniquely identifies an LRA globally. The node ID **MAY** be embedded in the URI path. If it is embedded, a coordinator receiving a request for an LRA whose embedded node ID does not match its own **MUST NOT** reject the request. It **MUST** either:

1. Load the LRA record from the distributed store and process the request directly (**RECOMMENDED**); or
2. Redirect the client with HTTP 307 Temporary Redirect to the node whose ID is embedded in the URI.

Option 1 is recommended because it removes the need for inter-node HTTP routing and simplifies load balancer configuration.

### 10.3 Cross-Node LRA Lookup

When a coordinator node receives a request for an LRA that is not in its local in-memory state, it **MUST**:

1. Extract the LRA identifier from the request URI.
2. Read the LRA record from the distributed store.
3. If found: deserialise and activate the record into the local in-memory state, then process the request.
4. If not found: return HTTP 404 Not Found.

### 10.4 Participant Replication

When a participant registers with a coordinator node, the participant's callback URLs and recovery URL **MUST** be written to the distributed store as part of the LRA record update before the registration response is returned. This ensures any other node can resume participant callbacks if the registering node crashes.

### 10.5 Acceptance Criteria

**AC-10.1 — Request handled by non-originating node**  
Given LRA "lra-001" was started on coordinator node A,  
when coordinator node B receives a close request for "lra-001",  
then node B closes "lra-001" successfully by loading the record from the distributed store and returns HTTP 200 OK.

**AC-10.2 — Participant registered on one node is callable from another**  
Given participant P registered with coordinator node A for LRA "lra-001",  
when coordinator node B drives the close of "lra-001",  
then node B sends the `complete` callback to participant P using the callback URLs stored in the distributed store.

---

## 11. Leader Election and Recovery Authority

### 11.1 Purpose

Recovery is the process of scanning the state store for LRAs not in a terminal state and driving them to completion or cancellation. A single **recovery leader** is elected to perform recovery scans, to avoid duplicate concurrent callbacks to participants.

Note: the recovery leader role applies **only** to recovery operations. Normal LRA start, join, close, and cancel requests are handled by any node and are not restricted to the recovery leader.

### 11.2 Leader Election Requirements

The implementation **MUST** elect exactly one recovery leader at any point in time. The election algorithm **MUST** satisfy:

- **Safety**: At most one node considers itself the recovery leader at any moment.
- **Liveness**: If the current leader leaves the cluster (crash or graceful stop), a new leader MUST be elected within the configured timeout (see NFR-02 and §16).
- **Determinism**: Given the same cluster membership view, all nodes agree on the identity of the leader.

### 11.3 Leader Transition Behaviour

When a node **becomes** the recovery leader:

1. It **MUST** log the transition at INFO level, including its node ID.
2. It **MUST** trigger an immediate recovery scan without waiting for the next scheduled interval.
3. It **MUST** acquire a distributed lock for each LRA it processes during recovery (see §14).

When a node **loses** the recovery leader role:

1. It **MUST** stop scheduling new recovery scans immediately.
2. It **MUST NOT** begin processing the next LRA in an in-progress scan once it determines it is no longer the leader. It **SHOULD** allow the currently processing LRA to complete.

### 11.4 Acceptance Criteria

**AC-11.1 — Single leader at all times**  
Given a three-node cluster in stable operation,  
when all three nodes are queried for their leader status simultaneously,  
then exactly one node reports itself as recovery leader and the other two report follower status.

**AC-11.2 — Leader re-election after crash**  
Given coordinator node A is the recovery leader in a three-node cluster,  
when node A crashes,  
then within the configured leader election timeout, exactly one of the remaining two nodes becomes the recovery leader and triggers an immediate recovery scan.

**AC-11.3 — No recovery scan on follower**  
Given coordinator node B is a follower in a three-node cluster,  
when the periodic recovery scan interval elapses,  
then node B does not initiate a recovery scan.

---

## 12. LRA Lifecycle Semantics in a Distributed Cluster

### 12.1 Starting an LRA

Any node **MUST** be able to start a new LRA. The LRA record **MUST** be written to the distributed store before the HTTP response is returned to the client. A store write failure **MUST** cause HTTP 500 Internal Server Error; the LRA **MUST NOT** be visible to clients in an unwritten state.

### 12.2 Joining an LRA (Participant Registration)

Any node **MUST** be able to accept participant registration for any LRA. If the LRA is not in the node's local in-memory state, it **MUST** be loaded from the distributed store (§10.3) before the participant is added. The updated record **MUST** be written to the distributed store before responding. A write failure **MUST** cause HTTP 500; the participant **MUST NOT** be considered registered.

### 12.3 Closing or Cancelling an LRA

Any node **MUST** be able to close or cancel any LRA. The node **MUST**:

1. Acquire the distributed lock for the LRA (§14).
2. Load the current LRA record from the distributed store.
3. Verify the LRA is in a state that permits the requested transition.
4. Transition the LRA to the next state (Closing or Cancelling).
5. Persist the updated record to the distributed store.
6. Send participant callbacks (`complete` or `compensate`).
7. Transition to the terminal state and persist the final record.
8. Release the distributed lock.

If the lock cannot be acquired within the configured timeout, the node **MUST** return HTTP 503 Service Unavailable. The client **MUST** retry.

### 12.4 Querying LRA Status

Any node **MUST** be able to serve an LRA status query by loading the record from the distributed store if it is not in local in-memory state. The response **MUST** reflect the latest persisted state.

### 12.5 Nested LRAs

Nested LRA semantics follow the MicroProfile LRA specification. The parent LRA record **MUST** contain the identifiers of all child LRAs. On close or cancel, the node handling the parent **MUST** load each child record from the distributed store and drive it to the correct terminal state.

### 12.6 Acceptance Criteria

**AC-12.1 — Start persists before response**  
Given a coordinator node receives an LRA start request,  
when it returns HTTP 201 Created,  
then reading the LRA identifier from the distributed store on any node returns a record in Active state.

**AC-12.2 — Close on non-originating node**  
Given LRA "lra-001" (Active, one participant) was started on node A,  
when node B receives a close request for "lra-001",  
then node B transitions it to Closed, persists the final state, and the participant receives exactly one `complete` callback.

**AC-12.3 — Lock contention on close returns 503**  
Given coordinator node A holds the distributed lock on LRA "lra-001",  
when coordinator node B receives a close request for "lra-001" and cannot acquire the lock within the configured timeout,  
then node B returns HTTP 503 Service Unavailable.

**AC-12.4 — Registration write failure is not silently accepted**  
Given the distributed store becomes unavailable after a participant registration request is received but before the write completes,  
when the write fails,  
then the coordinator returns HTTP 500 and the participant is not listed in the LRA record.

---

## 13. Recovery Protocol

### 13.1 Trigger Conditions

| Trigger | Who drives recovery |
|---------|---------------------|
| Periodic scheduled interval | Recovery leader only. |
| Node becomes recovery leader | New leader immediately, once, without waiting for the next interval. |
| Node startup (HA enabled) | The starting node performs a local scan of LRAs it finds in the store. It defers cluster-wide recovery to the elected leader. |

### 13.2 Recovery Scan Requirements

The recovery scan **MUST** satisfy the following observable requirements:

1. The recovery leader **MUST** attempt to drive every non-terminal LRA found in the state store to a terminal state.
2. For each LRA it intends to process, the leader **MUST** attempt to acquire the distributed lock before reading or modifying the record. If the lock cannot be acquired, the leader **MUST** skip that LRA for this scan cycle and log the skip at DEBUG level.
3. After each state transition during recovery, the leader **MUST** persist the updated state to the store before issuing participant callbacks.
4. The recovery scan **MUST NOT** block waiting for any individual lock or participant callback. If a participant is unresponsive, the LRA is left in its current recovery state and retried in the next scan cycle.
5. If an LRA has exceeded the configured maximum recovery attempts, it **MUST** be moved to a dedicated failed-LRA area in the store and excluded from future scan cycles.

### 13.3 Recovery of a Single LRA

When the recovery process drives a specific LRA, it **MUST**:

1. Load the current record from the distributed store.
2. If the LRA is already in a terminal state, release the lock and stop.
3. Contact each non-terminal participant's status endpoint to determine the current state.
4. Based on participant responses and the LRA's intended outcome, send the appropriate callback (`complete` or `compensate`).
5. Persist the updated participant and LRA state after each callback response.
6. If all participants reach a terminal state, move the LRA to its terminal state and persist the final record.
7. If the maximum recovery attempts are exceeded, move the LRA to `FailedToClose` or `FailedToCancel`.

### 13.4 Failed LRA Handling

LRAs that have exhausted recovery attempts **MUST** be moved to a dedicated failed-LRA area in the state store. This area **MUST** have a distinct key prefix from active LRAs so that recovery scans do not re-process them. Failed LRAs **MUST** remain queryable by operators.

### 13.5 Recovery After Full Cluster Restart

If all coordinator nodes are simultaneously stopped and restarted:

1. Leader election **MUST** complete before the elected leader begins a cluster-wide recovery scan.
2. Nodes **MUST NOT** remove any LRA record from the distributed store until that LRA has reached a terminal state.
3. The distributed state store **MUST** retain all LRA records across the full restart (see §9.5).

### 13.6 Acceptance Criteria

**AC-13.1 — Recovery after leader crash mid-scan**  
Given coordinator node A (recovery leader) acquires the lock on LRA "lra-001" and crashes before completing recovery,  
when node B becomes the new recovery leader and the lock TTL expires,  
then node B acquires the lock on "lra-001", drives it to a terminal state, and persists the final record.

**AC-13.2 — Failed LRA is not re-processed**  
Given LRA "lra-002" has exceeded the maximum recovery attempts and been moved to the failed-LRA area,  
when the next recovery scan runs,  
then "lra-002" is not processed by the scan and remains in the failed-LRA area.

**AC-13.3 — Recovery after full cluster restart**  
Given a three-node coordinator cluster with one active LRA in non-terminal state is fully stopped,  
when all three nodes restart,  
then the LRA is recovered by the elected recovery leader and driven to a terminal state without manual intervention.

**AC-13.4 — Skipped LRA is retried**  
Given coordinator node A cannot acquire the lock on LRA "lra-003" during a recovery scan (locked by another operation),  
when the next recovery scan cycle starts,  
then node A attempts recovery of "lra-003" again.

---

## 14. Distributed Locking

### 14.1 Purpose

The distributed lock prevents two coordinator nodes from concurrently modifying the same LRA record. It is required during recovery and during normal close/cancel operations.

### 14.2 Lock Protocol Requirements

**Acquire:**
The lock acquisition operation **MUST** be atomic and linearisable. It **MUST** succeed only if no lock entry for the given LRA identifier is currently present. On success, the lock entry **MUST** carry a TTL. The acquiring node's ID **MUST** be stored as the lock value.

**Release:**
The lock release operation **MUST** remove the lock entry only if its current value equals the releasing node's ID. This prevents a node from accidentally releasing a lock it no longer owns after TTL expiry and re-acquisition by another node.

**TTL:**
- **MUST** be configurable (see §16).
- **MUST** be ≥ 10 seconds.
- Default **MUST** be 30 seconds.
- **MUST** be long enough to cover the maximum expected participant callback round-trip time plus a safety margin.

### 14.3 Behaviour When Lock Cannot Be Acquired

- **During normal close/cancel**: return HTTP 503 Service Unavailable after the configured lock acquisition timeout. The client **MUST** retry.
- **During recovery scan**: skip the LRA for this cycle. **MUST NOT** block. Log the skip at DEBUG level.

### 14.4 Acceptance Criteria

See AC-9.4 and AC-9.5 in §9.6 (lock exclusivity and TTL expiry), and AC-12.3 in §12.6 (503 on lock contention). No additional criteria.

---

## 15. Failure Scenarios and Required Behaviour

### 15.1 Coordinator Node Crash (Minority Failure)

**Scenario:** One of three coordinator nodes crashes.

**Required behaviour:**

- The remaining two nodes form a quorum and **MUST** continue processing all LRA requests.
- If the crashed node was the recovery leader, a new leader **MUST** be elected within the configured timeout (NFR-02).
- The new leader **MUST** immediately run a recovery scan to cover any LRAs the crashed node was processing.
- All LRA records written before the crash **MUST** remain available in the distributed store on the surviving nodes.

**Acceptance criteria:**

**AC-15.1 — Continued service after minority crash**  
Given a three-node cluster where node A crashes,  
when a client sends an LRA start request to node B,  
then node B returns HTTP 201 Created and the LRA is persisted on both B and C.

### 15.2 Coordinator Node Graceful Shutdown

**Scenario:** A coordinator node is gracefully stopped (e.g. Kubernetes rolling update).

**Required behaviour:**

- The stopping node **SHOULD** release any distributed locks it holds, allowing other nodes to pick up those LRAs.
- If the stopping node is the recovery leader, it **SHOULD** allow its current recovery scan to finish before shutting down.
- The stopping node **MUST** deregister from the cluster membership service to trigger leader re-election if applicable.

### 15.3 Network Partition (Split-Brain)

**Scenario:** The cluster is split into two groups, each unable to communicate with the other.

**Required behaviour:**

- The **minority partition** (fewer nodes than quorum) **MUST** refuse all write operations and return HTTP 503 Service Unavailable for LRA start, join, close, and cancel. Read-only status queries are permitted.
- The **majority partition** **MUST** continue operating normally and elect a new recovery leader if the previous leader was in the minority.
- When the partition heals, nodes in the minority **MUST** reconcile their state with the majority partition. The reconciliation policy **MUST** prioritise the majority-partition state to prevent split-brain data divergence.

**Acceptance criteria:**

**AC-15.2 — Minority partition refuses writes**  
Given a three-node cluster is partitioned into {A} and {B, C},  
when a client sends an LRA start request to node A,  
then node A returns HTTP 503 Service Unavailable.

**AC-15.3 — Majority partition continues normally**  
Given the same partition as AC-15.2,  
when a client sends an LRA start request to node B,  
then node B returns HTTP 201 Created.

### 15.4 Distributed Store Unavailability

**Scenario:** The distributed store becomes temporarily unreachable from all coordinator nodes.

**Required behaviour:**

- All coordinator nodes **MUST** stop processing write operations and return HTTP 503 for LRA start, join, close, and cancel.
- Recovery scans **MUST** be paused until the store becomes available.
- When the store becomes available again, coordinator nodes **MUST** resume normal operation automatically. No LRA data **MUST** be lost if the store uses durable replication (see §9.5).

### 15.5 Duplicate Participant Callbacks

**Scenario:** A participant callback times out or the coordinator crashes after sending the callback but before recording the participant's response.

**Required behaviour:**

- The coordinator **MUST** re-send the callback during the next recovery scan.
- Before re-sending, the coordinator **MUST** query the participant's status endpoint to determine its current state and avoid sending a redundant callback.
- Per ASSUME-01, participants are assumed to be idempotent; the coordinator takes this as given and does not need to prevent duplicates beyond the status-check.

---

## 16. Configuration Reference

The following properties are technology-neutral and **MUST** be supported by any conformant implementation. Implementations **MAY** add technology-specific properties (e.g. for a specific state store backend) provided they are documented separately and do not conflict with this set.

| Property | Default | Type | Description |
|----------|---------|------|-------------|
| `lra.coordinator.ha.enabled` | `false` | boolean | Master switch. When `false`, the coordinator operates in single-instance mode with no distributed store dependency. |
| `lra.coordinator.cluster.name` | `lra-cluster` | String | Name of the coordinator cluster. Nodes with the same cluster name join the same cluster. |
| `lra.coordinator.node.id` | `$HOSTNAME` | String | Unique identifier for this coordinator node. MUST be stable across restarts. |
| `lra.coordinator.lock.ttl.seconds` | `30` | int | TTL in seconds for distributed locks on LRA identifiers. MUST be ≥ 10. |
| `lra.coordinator.leader.election.timeout` | `10` | int | Maximum seconds to wait for a new recovery leader to be elected after the previous leader leaves the cluster. |
| `lra.coordinator.recovery.interval.seconds` | `120` | int | Interval between periodic recovery scans (recovery leader only). |
| `lra.coordinator.recovery.max.attempts` | `10` | int | Maximum recovery attempts before moving an LRA to the failed-LRA store area. |
| `lra.coordinator.recovery.lock.timeout.ms` | `5000` | int | Milliseconds to wait for a distributed lock during normal close/cancel before returning HTTP 503. |

---

## 17. Conformance Requirements

All requirements in this specification use keywords as defined in RFC 2119:

- **MUST / MUST NOT**: Absolute requirement. Non-conformant if not satisfied.
- **SHOULD / SHOULD NOT**: Recommended. Deviations require documented justification.
- **MAY**: Optional.

### 17.1 Mandatory Conformance Checklist

An implementation is conformant with this specification if it satisfies all of the following:

- [ ] C-01: Every coordinator node has a unique, stable node ID.
- [ ] C-02: LRA records are written to the distributed store before the API response is returned to the client.
- [ ] C-03: Writes are not acknowledged until quorum acknowledgement is received from the distributed store.
- [ ] C-04: Any coordinator node can load and process any LRA from the distributed store.
- [ ] C-05: Exactly one recovery leader exists at all times in an operational cluster.
- [ ] C-06: The recovery leader triggers an immediate recovery scan upon becoming leader.
- [ ] C-07: Distributed locks are acquired with a TTL and released using conditional-remove semantics.
- [ ] C-08: The minority partition refuses write operations under a network partition.
- [ ] C-09: Single-node mode (`lra.coordinator.ha.enabled=false`) operates without any distributed store dependency.
- [ ] C-10: The MicroProfile LRA TCK passes without modification when HA mode is enabled.
- [ ] C-11: Failed LRAs (exhausted recovery attempts) are moved to a dedicated failed-LRA store area, not deleted.
- [ ] C-12: The state store enumeration operation returns all LRA identifiers, including those created by other nodes.
- [ ] C-13: The distributed state store is persistent across a full coordinator cluster restart.

### 17.2 MicroProfile LRA Specification Compliance

All coordinator behaviour **MUST** comply with the MicroProfile LRA specification. In particular:

- Coordinator REST API endpoints MUST match the specification's URL scheme and HTTP semantics.
- LRA state transitions MUST follow the specification's state machine (Active → Closing/Cancelling → Closed/Cancelled/FailedToClose/FailedToCancel).
- Participant callback contracts (idempotency, required endpoints) are governed by the MicroProfile LRA specification; this spec adds no new requirements on participants beyond ASSUME-01.
- Any extension path segments used for distributed routing (e.g. embedding node IDs) MUST NOT conflict with the specification's reserved path segments.

---

## Appendix A — MicroProfile LRA Spec Compliance Notes

*This appendix is informative. It does not add normative requirements beyond those in §17.2.*

### A.1 State Machine

The MicroProfile LRA specification mandates the following state transitions:

```
Active ──(close)──▶ Closing ──(all complete)──▶ Closed
Active ──(cancel)─▶ Cancelling ──(all compensate)──▶ Cancelled
Active ──(timeout)─▶ Cancelling
Closing ──(participant failure)──▶ FailedToClose
Cancelling ──(participant failure)──▶ FailedToCancel
```

In a distributed coordinator, after every state transition the new state MUST be written to the distributed store before participant callbacks are sent. If the write fails, the transition MUST be rolled back and the client notified with HTTP 500.

### A.2 Required REST Endpoints

Per the MicroProfile LRA specification, a coordinator MUST expose:

```
POST   /lra-coordinator                    Start LRA
GET    /lra-coordinator                    List LRAs
GET    /lra-coordinator/{lraId}            Get LRA status
PUT    /lra-coordinator/{lraId}/close      Close LRA
PUT    /lra-coordinator/{lraId}/cancel     Cancel LRA
PUT    /lra-coordinator/{lraId}            Join LRA (participant registration)
DELETE /lra-coordinator/{lraId}            Leave LRA
GET    /lra-coordinator/{lraId}/status     Get LRA status (short form)
```

In distributed mode, all endpoints MUST work on any node.

### A.3 Recovery Coordinator Endpoint

The recovery coordinator URL is embedded in participant link headers:

```
Link: <http://coordinator-host/lra-coordinator/recovery/{lraId}/{participantId}>; rel="lra-participant"
```

In distributed mode, the recovery URL MUST be reachable on any coordinator node. When a node receives a recovery notification for an LRA it does not hold in memory, it MUST load the LRA from the distributed store and process the notification.

---

## Appendix B — Worked Example with Three Nodes (Informative)

*This appendix is informative only and does not add normative requirements.*

### Cluster Setup

- Three coordinator nodes: A (recovery leader), B, C.
- HA mode enabled; distributed state store replicated across all three nodes.
- A client microservice initiates an order saga.

### Step 1 — LRA Start (handled by Node B)

```
Client → POST /lra-coordinator → Node B
Node B:
  1. Creates LRA identifier "lra-001"
  2. Writes record to distributed store → replicated to A and C
  3. Returns 201 Created: Location: http://node-b/lra-coordinator/lra-001
```

### Step 2 — Participant Registration (handled by Node C)

```
Payment service → PUT /lra-coordinator/lra-001 → Node C
Node C:
  1. "lra-001" not in local in-memory state
  2. Reads record from distributed store → found (written by B)
  3. Adds participant link to record
  4. Writes updated record to store → replicated to A and B
  5. Returns 200 OK
```

### Step 3 — Node A (Recovery Leader) Crashes

```
Node A crashes mid-scan.
Membership view changes: {A, B, C} → {B, C}
Leader election: B becomes the new recovery leader.
Node B triggers an immediate recovery scan.
```

### Step 4 — Client Requests Close (handled by Node B)

```
Client → PUT /lra-coordinator/lra-001/close → Node B
Node B:
  1. Acquires distributed lock on "lra-001" (TTL 30 s)
  2. Reads latest record from store (includes payment participant)
  3. Transitions: Active → Closing; writes to store
  4. Sends "complete" callback to payment service → 200 OK
  5. Transitions: Closing → Closed; writes final record to store
  6. Releases lock
  7. Returns 200 OK to client
```

### Step 5 — Node A Restarts and Rejoins

```
Node A restarts and joins the cluster: {B, C} → {A, B, C}
B remains recovery leader (determined by membership view ordering).
Node A reads "lra-001" from store → status is Closed → no recovery needed.
```

This example shows that no data is lost and the LRA completes correctly despite the coordinator crash because:

- State was written to the distributed store before any callbacks were sent (AC-9.1).
- Leader re-election and an immediate recovery scan cover LRAs in-progress at crash time (AC-11.2).
- Distributed locks prevent concurrent processing of the same LRA (AC-9.4).

---

*End of Specification*

---

*Document maintained by the Narayana LRA team. Contributions and feedback via the project issue tracker.*
