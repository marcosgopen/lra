# LRA High Availability - Documentation Index

## Overview

This directory contains documentation for the **LRA Coordinator High Availability** implementation using Infinispan and JGroups.

## Quick Start

**Want to enable HA?** See: [HA-SUMMARY.md](HA-SUMMARY.md)

**Deploying to Kubernetes?** See: [HA-IMPLEMENTATION.md](HA-IMPLEMENTATION.md#kubernetes-deployment-example)

**Network partition questions?** See: [PARTITION-HANDLING-SUMMARY.md](PARTITION-HANDLING-SUMMARY.md)

## Documentation Files

### Core Documentation

1. **[HA-SUMMARY.md](HA-SUMMARY.md)** - Start here!
   - What was implemented (shared object store, any coordinator manages any LRA)
   - Why JGroups coordinator instead of Raft
   - Architecture diagrams
   - Configuration and testing

2. **[HA-IMPLEMENTATION.md](HA-IMPLEMENTATION.md)** - Deep dive
   - Complete architecture details
   - How each component works (Infinispan, locks, coordinator election)
   - LRA lifecycle in HA mode
   - Kubernetes deployment examples
   - Benefits and trade-offs

3. **[CHANGELOG-HA.md](CHANGELOG-HA.md)** - What changed
   - Complete list of files created/modified
   - Network partition handling changes
   - Migration path from single-instance

### Network Partition Handling

4. **[NETWORK-PARTITION-HANDLING.md](NETWORK-PARTITION-HANDLING.md)** - Complete guide
   - How Infinispan handles partitions (automatic!)
   - Partition scenarios (2+1, 2+2, merge)
   - Best practices (odd number of nodes)
   - Testing and monitoring

5. **[PARTITION-HANDLING-SUMMARY.md](PARTITION-HANDLING-SUMMARY.md)** - Quick reference
   - What Infinispan provides out-of-the-box
   - What we added (availability checks)
   - Client experience during partitions

### Transaction Semantics

6. **[ACID-AND-SAGA-PROPERTIES.md](ACID-AND-SAGA-PROPERTIES.md)** - ACID vs SAGA
   - **Important:** LRAs are NOT traditional ACID transactions!
   - SAGA pattern semantics (eventual consistency, compensations)
   - How HA implementation maintains SAGA guarantees
   - Durability, atomicity through compensation
   - Best practices for LRA applications

### Configuration

7. **[application-ha.properties.example](src/main/resources/application-ha.properties.example)** - Config examples
   - Enable HA mode
   - Cluster configuration
   - JGroups settings
   - Kubernetes notes

## Key Concepts

### What is LRA?

**Long Running Action (LRA)** implements the **SAGA pattern** for distributed transactions:
- Designed for **long-lived workflows** (seconds to hours/days)
- **Eventual consistency** (not immediate)
- **Compensation** instead of rollback
- Works across **microservices boundaries**

See: [ACID-AND-SAGA-PROPERTIES.md](ACID-AND-SAGA-PROPERTIES.md)

### What is HA Mode?

**High Availability** allows **multiple LRA coordinators** to work together:
- **Shared object store** with node ID embedding
- **Any coordinator can manage any LRA** (distributed state + locks)
- **Automatic coordinator election** (JGroups, simpler than Raft)
- **Network partition tolerance** (Infinispan built-in)
- **Cloud-native** (Kubernetes/OpenShift ready)

See: [HA-SUMMARY.md](HA-SUMMARY.md)

### How Are Network Partitions Handled?

**Automatically by Infinispan** using `DENY_READ_WRITES` strategy:
- **Majority partition** (>50% nodes): Continues normal operation
- **Minority partition** (≤50% nodes): Denies all operations (returns 503)
- **Automatic merge**: When network heals, conflicts resolved automatically

See: [PARTITION-HANDLING-SUMMARY.md](PARTITION-HANDLING-SUMMARY.md)

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                Load Balancer / Service Mesh                  │
└────────────┬───────────────┬────────────────┬───────────────┘
             │               │                │
         ┌───▼────┐      ┌───▼────┐      ┌───▼────┐
         │ LRA    │      │ LRA    │      │ LRA    │
         │ Coord  │      │ Coord  │      │ Coord  │
         │   #1   │      │   #2   │      │   #3   │
         │(COORD) │      │        │      │        │
         └───┬────┘      └───┬────┘      └───┬────┘
             │               │                │
             └───────┬───────┴────────┬───────┘
                     │                │
        ┌────────────▼─────┐  ┌──────▼──────────┐
        │    Infinispan    │  │  Shared Object  │
        │   (Replicated)   │  │     Store       │
        │                  │  │  (node1/, ...)  │
        │ - Active LRAs    │  │                 │
        │ - Recovering     │  │                 │
        │ - Failed         │  │                 │
        └──────────────────┘  └─────────────────┘
```

See: [HA-IMPLEMENTATION.md](HA-IMPLEMENTATION.md#architecture-overview)

## Components

| Component | Purpose | Documentation |
|-----------|---------|---------------|
| **InfinispanStore** | Distributed LRA state storage | [HA-IMPLEMENTATION.md](HA-IMPLEMENTATION.md#components) |
| **DistributedLockManager** | Cluster-wide LRA locking | [HA-IMPLEMENTATION.md](HA-IMPLEMENTATION.md#components) |
| **ClusterCoordinator** | JGroups coordinator election | [HA-IMPLEMENTATION.md](HA-IMPLEMENTATION.md#components) |
| **LRAState** | Serializable LRA representation | [HA-IMPLEMENTATION.md](HA-IMPLEMENTATION.md#components) |

## Configuration Examples

### Enable HA Mode

```properties
lra.coordinator.ha.enabled=true
lra.coordinator.cluster.name=lra-cluster
lra.coordinator.node.id=lra-coord-1  # Optional
```

See: [application-ha.properties.example](src/main/resources/application-ha.properties.example)

### Kubernetes Deployment

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: lra-coordinator
spec:
  replicas: 3  # Use odd number!
  serviceName: lra-coordinator
  template:
    spec:
      containers:
      - name: coordinator
        env:
        - name: LRA_COORDINATOR_HA_ENABLED
          value: "true"
```

See: [HA-IMPLEMENTATION.md](HA-IMPLEMENTATION.md#kubernetes-deployment-example)

## Transaction Guarantees

| Property | Traditional ACID | LRA SAGA | HA Implementation |
|----------|-----------------|----------|-------------------|
| **Atomicity** | All-or-nothing via rollback | Compensation | Recovery ensures all compensators called |
| **Consistency** | Immediate | Eventual | Sync replication (REPL_SYNC) |
| **Isolation** | Serializable | Read uncommitted | Distributed locks for metadata |
| **Durability** | Committed = permanent | State persists | Dual persistence + replication |

See: [ACID-AND-SAGA-PROPERTIES.md](ACID-AND-SAGA-PROPERTIES.md)

## Testing

### Test HA Failover

```bash
# Start LRA on node1
LRA_ID=$(curl -X POST http://node1:8080/lra-coordinator/start)

# Kill node1
kill <node1-pid>

# Close LRA from node2 (succeeds!)
curl -X PUT http://node2:8080/lra-coordinator/${LRA_ID}/close
```

See: [HA-SUMMARY.md](HA-SUMMARY.md#testing-the-implementation)

### Test Network Partition

```bash
# Block traffic to node3
iptables -A INPUT -s <node1-ip> -j DROP
iptables -A INPUT -s <node2-ip> -j DROP

# Try operation on node3 (minority)
curl -X POST http://node3:8080/lra-coordinator/start
# Expected: 503 Service Unavailable

# Restore network
iptables -F
```

See: [NETWORK-PARTITION-HANDLING.md](NETWORK-PARTITION-HANDLING.md#testing-partition-handling)

## Best Practices

### 1. Use Odd Number of Nodes

✅ **Good:** 3, 5, 7 nodes - guarantees majority in any split
❌ **Bad:** 2, 4, 6 nodes - risk of 50/50 split (all nodes DEGRADED)

### 2. Configure Health Checks

```yaml
readinessProbe:
  httpGet:
    path: /health
    port: 8080
  periodSeconds: 10
```

Routes traffic away from nodes in minority partition

### 3. Design Idempotent Compensators

```java
@Compensate
public Response compensate(URI lraId) {
    if (alreadyCompensated(lraId)) {
        return Response.ok().build();  // Idempotent!
    }
    undoWork(lraId);
    return Response.ok().build();
}
```

Recovery may call compensate multiple times

See: [ACID-AND-SAGA-PROPERTIES.md](ACID-AND-SAGA-PROPERTIES.md#best-practices-for-lra-applications)

## FAQ

### Q: Are LRAs ACID transactions?

**No!** LRAs implement the **SAGA pattern**:
- **Eventual** consistency (not immediate)
- **Compensation** (not rollback)
- **Relaxed** isolation (not serializable)

See: [ACID-AND-SAGA-PROPERTIES.md](ACID-AND-SAGA-PROPERTIES.md)

### Q: How are network partitions handled?

**Automatically by Infinispan:**
- Majority partition continues
- Minority partition denies operations
- Automatic merge when healed

See: [PARTITION-HANDLING-SUMMARY.md](PARTITION-HANDLING-SUMMARY.md)

### Q: Why JGroups coordinator instead of Raft?

**Simpler and sufficient:**
- JGroups coordinator election (built-in)
- Infinispan handles data replication
- No need for consensus protocol

See: [HA-SUMMARY.md](HA-SUMMARY.md#why-jgroups-coordinator-instead-of-raft)

### Q: What happens if coordinator crashes during LRA close?

**Recovery takes over:**
- LRA state loaded from ObjectStore + Infinispan
- Recovery module retries participant calls
- Eventually completes

See: [ACID-AND-SAGA-PROPERTIES.md](ACID-AND-SAGA-PROPERTIES.md#2-coordinator-failure-during-closecancel)

### Q: Can I migrate from single-instance to HA?

**Yes, it's backward compatible:**
- Single-instance still works (no config changes)
- Add `lra.coordinator.ha.enabled=true` to enable HA
- No code changes required

See: [CHANGELOG-HA.md](CHANGELOG-HA.md#migration-path)

## Next Steps

### For Developers

1. Read [HA-SUMMARY.md](HA-SUMMARY.md) - understand what was implemented
2. Read [ACID-AND-SAGA-PROPERTIES.md](ACID-AND-SAGA-PROPERTIES.md) - understand LRA semantics
3. Review code in created classes (LRAState, InfinispanStore, etc.)

### For Operators

1. Read [HA-IMPLEMENTATION.md](HA-IMPLEMENTATION.md) - deployment architecture
2. Read [PARTITION-HANDLING-SUMMARY.md](PARTITION-HANDLING-SUMMARY.md) - partition behavior
3. Review [application-ha.properties.example](src/main/resources/application-ha.properties.example)
4. Deploy with odd number of nodes (3, 5, 7)
5. Configure health checks
6. Test failover scenarios

### For Production

- [ ] Add health check endpoint
- [ ] Add integration tests (HA scenarios)
- [ ] Add partition handling tests
- [ ] Add Prometheus metrics
- [ ] Create production JGroups config (KUBE_PING for K8s)
- [ ] Performance testing under load
- [ ] Disaster recovery procedures

## Support

- **Issues:** File at project issue tracker
- **Questions:** See documentation above
- **Contributing:** Follow project contribution guidelines

---

**Documentation Version:** 1.0
**Last Updated:** 2026-02-12
**Implementation Status:** Core HA complete, partition handling complete, production hardening pending
