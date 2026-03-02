# LRA High Availability - Documentation Index

## Overview

This directory contains documentation for the **LRA Coordinator High Availability** implementation using Infinispan and JGroups.

## Quick Start

**Want to enable HA?** Set `lra.coordinator.ha.enabled=true` and see [Configuration](#configuration-examples) below.

**Deploying to Kubernetes?** See [HA-IMPLEMENTATION.md](HA-IMPLEMENTATION.md#kubernetes-deployment-example)

## Documentation Files

1. **[HA-IMPLEMENTATION.md](HA-IMPLEMENTATION.md)** - Architecture and design
   - Complete architecture details
   - How each component works (Infinispan, locks, coordinator election)
   - LRA lifecycle in HA mode
   - Kubernetes deployment examples
   - Benefits and trade-offs

2. **[application-ha.properties.example](src/main/resources/application-ha.properties.example)** - Config examples
   - Enable HA mode
   - Cluster configuration
   - JGroups settings
   - Kubernetes notes

## Key Concepts

### What is HA Mode?

**High Availability** allows **multiple LRA coordinators** to work together:
- **Shared object store** with node ID embedding
- **Any coordinator can manage any LRA** (distributed state + locks)
- **Automatic coordinator election** (JGroups, simpler than Raft)
- **Network partition tolerance** (Infinispan built-in)
- **Cloud-native** (Kubernetes/OpenShift ready)

### How Are Network Partitions Handled?

**Automatically by Infinispan** using `DENY_READ_WRITES` strategy:
- **Majority partition** (>50% nodes): Continues normal operation
- **Minority partition** (<=50% nodes): Denies all operations (returns 503)
- **Automatic merge**: When network heals, conflicts resolved automatically

## Architecture Overview

```
+---------------------------------------------------------+
|                Load Balancer / Service Mesh              |
+------------+---------------+----------------+-----------+
             |               |                |
         +---v----+      +---v----+      +---v----+
         | LRA    |      | LRA    |      | LRA    |
         | Coord  |      | Coord  |      | Coord  |
         |   #1   |      |   #2   |      |   #3   |
         |(COORD) |      |        |      |        |
         +---+----+      +---+----+      +---+----+
             |               |                |
             +-------+-------+--------+-------+
                     |                |
        +------------v-----+  +------v----------+
        |    Infinispan    |  |  Shared Object  |
        |   (Replicated)   |  |     Store       |
        |                  |  |  (node1/, ...)  |
        | - Active LRAs    |  |                 |
        | - Recovering     |  |                 |
        | - Failed         |  |                 |
        +------------------+  +-----------------+
```

See: [HA-IMPLEMENTATION.md](HA-IMPLEMENTATION.md#architecture)

## Components

| Component | Purpose | Documentation |
|-----------|---------|---------------|
| **InfinispanStore** | Distributed LRA state storage | [HA-IMPLEMENTATION.md](HA-IMPLEMENTATION.md#components) |
| **InfinispanLockManager** | Cluster-wide LRA locking | [HA-IMPLEMENTATION.md](HA-IMPLEMENTATION.md#components) |
| **InfinispanClusterCoordinator** | JGroups coordinator election | [HA-IMPLEMENTATION.md](HA-IMPLEMENTATION.md#components) |
| **LRAState** | ProtoStream-serializable LRA representation | [HA-IMPLEMENTATION.md](HA-IMPLEMENTATION.md#components) |

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

## Testing

### Unit Tests

```bash
mvn test -pl coordinator -Dtest=HACoordinatorTest,SimpleHATest
```

21 tests covering: cache operations, state replication, distributed locking, cache availability, state transitions, and concurrent access.

### Manual HA Failover Test

```bash
# Start LRA on node1
LRA_ID=$(curl -X POST http://node1:8080/lra-coordinator/start)

# Kill node1
kill <node1-pid>

# Close LRA from node2 (succeeds!)
curl -X PUT http://node2:8080/lra-coordinator/${LRA_ID}/close
```

## Best Practices

### 1. Use Odd Number of Nodes

- **Good:** 3, 5, 7 nodes - guarantees majority in any split
- **Bad:** 2, 4, 6 nodes - risk of 50/50 split (all nodes DEGRADED)

### 2. Design Idempotent Compensators

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

Recovery may call compensate multiple times.

## FAQ

### Q: How are network partitions handled?

**Automatically by Infinispan:**
- Majority partition continues
- Minority partition denies operations
- Automatic merge when healed

### Q: Why JGroups coordinator instead of Raft?

**Simpler and sufficient:**
- JGroups coordinator election (built-in)
- Infinispan handles data replication
- No need for consensus protocol

### Q: What happens if coordinator crashes during LRA close?

**Recovery takes over:**
- LRA state loaded from ObjectStore + Infinispan
- Recovery module retries participant calls
- Eventually completes

### Q: Can I migrate from single-instance to HA?

**Yes, it's backward compatible:**
- Single-instance still works (no config changes)
- Add `lra.coordinator.ha.enabled=true` to enable HA
- No code changes required

## Future Work

- [ ] Integration tests (multi-node Arquillian)
- [ ] Deployment guides (Quarkus, WildFly)
- [ ] Health check endpoint
- [ ] Prometheus metrics
- [ ] Production JGroups config (KUBE_PING for K8s)
- [ ] Performance testing under load
