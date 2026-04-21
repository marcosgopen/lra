# ADR-001: Use Infinispan Over JGroups-Raft for LRA Coordinator HA

## Status

**ACCEPTED** - 2026-03-05

## Context

The Narayana LRA (Long Running Actions) coordinator requires high availability (HA) to ensure reliable distributed transaction coordination across multiple nodes. We evaluated two approaches for implementing HA:

1. **Current Implementation**: Infinispan distributed caches with JGroups clustering (eventual consistency)
2. **Alternative**: JGroups-Raft ReplicatedStateMachine (strong consistency via RAFT consensus)

### LRA/SAGA Pattern Characteristics

LRA implements the SAGA pattern for distributed transactions with these key properties:

- **Compensating Transactions**: Each step has a compensation action for rollback
- **Long-Running**: Transactions can span minutes, hours, or even days
- **Eventually Consistent by Design**: SAGAs accept temporary inconsistency between services
- **At-Least-Once Semantics**: Operations are idempotent and can be retried
- **State Machine**: Active → Completing → Completed/Compensating → Compensated/Failed

### Problem Statement

Should we migrate from Infinispan's eventual consistency model to JGroups-Raft's strong consistency model for LRA coordinator state management?

## Decision

**We will continue using Infinispan distributed caches for LRA coordinator HA.**

### Rationale

#### 1. Philosophical Alignment

**SAGAs are inherently eventually consistent patterns.** The SAGA pattern was designed to handle distributed transactions WITHOUT requiring strong consistency guarantees. Key principles:

- Compensating transactions handle inconsistencies
- Each service maintains local consistency
- Global consistency is achieved eventually through compensation
- Strong consistency at the coordinator level doesn't eliminate eventual consistency at the service level

**Conclusion**: Adding strong consistency to the coordinator doesn't align with SAGA's fundamental design philosophy.

#### 2. Performance Characteristics

| Metric | Infinispan (Current) | JGroups-Raft | Impact |
|--------|---------------------|--------------|--------|
| Write Latency | 10-20ms | 30-50ms | **2-3x slower** |
| Read Latency | <5ms | <5ms (from leader) | Similar |
| Throughput | High | Moderate | **Reduced** |
| Network Overhead | Moderate | Higher (log replication) | **Increased** |

**For LRA coordination:**
- State transitions happen frequently (Active→Completing→Completed)
- Each participant callback triggers state updates
- High-throughput scenarios (100s of concurrent LRAs) would suffer
- Long-running LRAs amplify latency costs over time

**Conclusion**: RAFT's consensus overhead provides no benefit for LRA's use case while significantly degrading performance.

#### 3. Availability and Fault Tolerance

| Aspect | Infinispan | JGroups-Raft | Winner |
|--------|-----------|--------------|--------|
| Failure Tolerance | N-1 nodes | (N-1)/2 nodes | **Infinispan** |
| 3-node cluster | Tolerates 2 failures | Tolerates 1 failure | **Infinispan** |
| 5-node cluster | Tolerates 4 failures | Tolerates 2 failures | **Infinispan** |
| Network Partition | Configurable (DENY_READ_WRITES) | Quorum required | **Infinispan** |
| Recovery Time | Fast (async replication) | Moderate (leader election) | **Infinispan** |

**For long-running LRAs:**
- LRAs can span hours/days - higher availability is critical
- Losing quorum during a long-running LRA is catastrophic
- Infinispan's partition handling (DENY_READ_WRITES) provides safety without sacrificing availability

**Conclusion**: Infinispan provides better availability for long-running transaction coordination.

#### 4. Consistency Requirements Analysis

**What LRA Actually Needs:**

✅ **Eventual Consistency** - Compensations handle temporary inconsistencies  
✅ **Idempotency** - Operations can be safely retried  
✅ **Durability** - State survives node failures (both provide this)  
✅ **Partition Tolerance** - Continue operating during network issues  

❌ **Linearizability** - NOT required (compensations provide correctness)  
❌ **Strict Ordering** - NOT required (LRAs can progress independently)  
❌ **Exactly-Once Semantics** - NOT required (at-least-once with idempotency)  

**RAFT Provides (but LRA doesn't need):**
- Linearizable reads/writes
- Total ordering of all operations
- Immediate consistency across all nodes
- Exactly-once state transitions

**Conclusion**: RAFT's guarantees are unnecessary for LRA's correctness model.

#### 5. Operational Complexity

| Aspect | Infinispan | JGroups-Raft |
|--------|-----------|--------------|
| Configuration | Moderate | Complex |
| Monitoring | Standard metrics | RAFT-specific metrics (log size, commit index, etc.) |
| Troubleshooting | Well-documented | Requires RAFT expertise |
| Backup/Restore | Cache snapshots | Log + snapshot management |
| Cluster Resizing | Dynamic | Requires careful quorum management |

**Conclusion**: Infinispan has lower operational overhead and better tooling support.

#### 6. Real-World Failure Scenarios

**Scenario 1: Network Partition**
- **Infinispan**: DENY_READ_WRITES prevents split-brain, both partitions remain available for reads
- **RAFT**: Minority partition becomes unavailable, LRAs in progress may fail

**Scenario 2: Coordinator Node Failure During LRA**
- **Infinispan**: Another node immediately takes over (N-1 tolerance)
- **RAFT**: Leader election required (30-50ms), potential timeout for in-flight operations

**Scenario 3: Rolling Upgrade**
- **Infinispan**: Nodes can be upgraded one at a time without downtime
- **RAFT**: Must maintain quorum, more complex upgrade procedure

**Conclusion**: Infinispan handles common failure scenarios more gracefully for LRA's use case.

## Consequences

### Positive

1. **Performance**: Maintain low-latency state transitions (10-20ms)
2. **Availability**: Higher fault tolerance (N-1 vs (N-1)/2)
3. **Simplicity**: No migration required, existing operational knowledge retained
4. **Cost**: No development/testing/migration costs
5. **Alignment**: Consistency model matches SAGA pattern philosophy

### Negative

1. **Eventual Consistency**: Brief windows where nodes may have slightly different views
   - **Mitigation**: Already handled by LRA's compensation mechanism
2. **No Linearizability**: Can't guarantee strict ordering of concurrent operations
   - **Mitigation**: LRA doesn't require this - compensations provide correctness

### Neutral

1. **Partition Handling**: Both approaches require careful configuration
2. **Monitoring**: Both require proper observability setup

## Alternatives Considered

### Alternative 1: Full Migration to JGroups-Raft

**Rejected** - Reasons outlined above. Strong consistency is unnecessary overhead for SAGA patterns.

### Alternative 2: Hybrid Approach

Use RAFT for critical metadata (cluster membership, configuration) and Infinispan for LRA state.

**Rejected** - Adds complexity without clear benefits:
- Two consistency models to manage
- Increased operational overhead
- LRA state is the primary data - metadata is less critical

### Alternative 3: Optimize Current Infinispan Setup

**RECOMMENDED** - Instead of migrating, improve the current implementation:

1. **Tune Replication**:
   ```xml
   <replicated-cache name="lra-active" mode="SYNC">
     <locking isolation="REPEATABLE_READ" acquire-timeout="10000"/>
     <transaction mode="NON_TRANSACTIONAL"/>
     <state-transfer enabled="true" timeout="60000"/>
   </replicated-cache>
   ```

2. **Add Monitoring**:
   - Cache hit/miss rates
   - Replication lag metrics
   - Partition detection alerts

3. **Improve Partition Handling**:
   - Fine-tune DENY_READ_WRITES strategy
   - Add custom merge policies if needed

4. **Optimize Serialization**:
   - Use Protostream for efficient serialization
   - Reduce object size for faster replication

## References

### SAGA Pattern
- [Original SAGA Paper (1987)](https://www.cs.cornell.edu/andru/cs711/2002fa/reading/sagas.pdf)
- [Microservices Patterns: SAGA](https://microservices.io/patterns/data/saga.html)

### LRA Specification
- [MicroProfile LRA Specification](https://github.com/eclipse/microprofile-lra)
- [Narayana LRA Documentation](https://narayana.io/lra/)

### Consistency Models
- [CAP Theorem](https://en.wikipedia.org/wiki/CAP_theorem)
- [Eventual Consistency](https://www.allthingsdistributed.com/2008/12/eventually_consistent.html)

### Technologies
- [Infinispan Documentation](https://infinispan.org/docs/stable/titles/overview/overview.html)
- [JGroups-Raft](https://github.com/jgroups-extras/jgroups-raft)
- [RAFT Consensus Algorithm](https://raft.github.io/)

## Implementation Notes

### Current Infinispan Configuration

```xml
<!-- lra-coordinator-ha-infinispan module -->
<replicated-cache name="lra-active" mode="SYNC">
  <partition-handling when-split="DENY_READ_WRITES"/>
</replicated-cache>
<replicated-cache name="lra-recovering" mode="SYNC">
  <partition-handling when-split="DENY_READ_WRITES"/>
</replicated-cache>
<replicated-cache name="lra-failed" mode="SYNC">
  <partition-handling when-split="DENY_READ_WRITES"/>
</replicated-cache>
```

### Key Classes
- `InfinispanStore`: Manages LRA state in Infinispan caches
- `InfinispanClusterCoordinator`: Handles cluster coordination

### Dependencies
```xml
<dependency>
  <groupId>org.infinispan</groupId>
  <artifactId>infinispan-core</artifactId>
  <version>16.1.0.Final</version>
</dependency>
<dependency>
  <groupId>org.jgroups</groupId>
  <artifactId>jgroups</artifactId>
  <version>5.5.2.Final</version>
</dependency>
```

## Decision Makers

- Architecture Team
- Narayana Development Team
- Operations Team

## Review Date

This decision should be reviewed if:
1. LRA requirements change to need strong consistency
2. Performance characteristics of RAFT significantly improve
3. New use cases emerge that require linearizability
4. Infinispan performance becomes a bottleneck

**Next Review**: 2027-03-05 (1 year)

---

## Appendix: When Would RAFT Be Appropriate?

RAFT (or similar consensus algorithms) would be appropriate for:

1. **Distributed Locks**: Require strict ordering and mutual exclusion
2. **Configuration Management**: Critical metadata that must be consistent
3. **Leader Election**: Single writer scenarios
4. **Exactly-Once Semantics**: When idempotency is not possible
5. **Audit Logs**: When strict ordering is legally required

**None of these apply to LRA coordinator state management.**
