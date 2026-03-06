# LRA Coordinator HA - Infinispan Module

## Overview

This module provides **High Availability** for the LRA Coordinator using Infinispan distributed caches and JGroups clustering. It is a **separate, optional module** — the core coordinator has zero dependency on Infinispan. Projects that do not need HA simply omit this module.

## Integration Guide

### Step 1: Add the Maven Dependency

Add the `lra-coordinator-ha-infinispan` artifact to your project:

```xml
<dependency>
  <groupId>org.jboss.narayana.lra</groupId>
  <artifactId>lra-coordinator-ha-infinispan</artifactId>
  <version>${version.lra}</version>
</dependency>
```

This pulls in the Infinispan runtime and all CDI beans automatically. The core `lra-coordinator-jar` dependency is transitive.

### Step 2: Enable HA Mode

Set the system property (or equivalent in your runtime):

```properties
lra.coordinator.ha.enabled=true
```

**Both** the dependency and the property are required:
- Without the dependency: the coordinator runs in single-instance mode (no Infinispan classes on classpath)
- Without the property: the Infinispan beans are on the classpath but not activated

### Step 3: Configure the Cluster

At minimum, set a cluster name and node ID:

```properties
lra.coordinator.ha.enabled=true
lra.coordinator.cluster.name=lra-cluster
lra.coordinator.node.id=lra-coord-1
```

See [application-ha.properties.example](src/main/resources/application-ha.properties.example) for all available properties.

## How It Works

When both the dependency and configuration are in place:

1. CDI discovers the `@ApplicationScoped` beans in this module (`InfinispanStore`, `InfinispanConfiguration`, `InfinispanLockManager`, `InfinispanClusterCoordinator`)
2. The coordinator's `LRARecoveryModule` looks up these beans via CDI and initializes HA mode
3. LRA state is replicated across the cluster via Infinispan caches
4. Distributed locks prevent concurrent modifications to the same LRA
5. JGroups coordinator election ensures only one node performs recovery

### When HA Is Disabled

- If this module is **not on the classpath**, CDI never sees the Infinispan beans — zero overhead
- If this module is on the classpath but `lra.coordinator.ha.enabled != true`, the `LRARecoveryModule` skips all CDI lookups — beans are registered but never instantiated

## Architecture

```
+---------------------------------------------------------+
|                Load Balancer / Service Mesh              |
+------------+---------------+----------------+-----------+
             |               |                |
         +---v----+      +---v----+      +---v----+
         | LRA    |      | LRA    |      | LRA    |
         | Coord  |      | Coord  |      | Coord  |
         |   #1   |      |   #2   |      |   #3   |
         |(LEADER)|      |        |      |        |
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

## Key Concepts

- **Any coordinator can manage any LRA** — state is shared via Infinispan
- **Automatic coordinator election** — JGroups view-based, simpler than Raft
- **Network partition tolerance** — Infinispan `DENY_READ_WRITES` strategy
- **Node ID embedding** — LRA IDs include the creating node's ID for object store isolation

## Components

| Component | Class | Purpose |
|-----------|-------|---------|
| Distributed Store | `InfinispanStore` | Three replicated caches (active, recovering, failed) |
| Distributed Locks | `InfinispanLockManager` | Cluster-wide LRA locking via Infinispan Clustered Locks |
| Leader Election | `InfinispanClusterCoordinator` | JGroups-based coordinator election for recovery |
| Serialization | `InfinispanLRAState` | ProtoStream-annotated `LRAState` implementation |
| CDI Configuration | `InfinispanConfiguration` | Cache manager and bean producers |

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `lra.coordinator.ha.enabled` | `false` | Master switch for HA mode |
| `lra.coordinator.cluster.name` | `lra-cluster` | JGroups cluster name |
| `lra.coordinator.node.id` | `$HOSTNAME` | Unique node identifier |
| `lra.coordinator.jgroups.config` | (default UDP) | Path to JGroups XML config |
| `lra.coordinator.jgroups.bind_addr` | `127.0.0.1` | JGroups bind address |
| `lra.coordinator.infinispan.persistent.location` | `$TMPDIR/lra-infinispan-$NODE` | Infinispan state directory |
| `lra.coordinator.infinispan.cache.mode` | `REPL_SYNC` | Cache mode (REPL_SYNC, DIST_SYNC, etc.) |

## Kubernetes Deployment

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: lra-coordinator
spec:
  serviceName: lra-coordinator
  replicas: 3  # Use odd number
  template:
    spec:
      containers:
      - name: coordinator
        env:
        - name: LRA_COORDINATOR_HA_ENABLED
          value: "true"
        - name: LRA_CLUSTER_NAME
          value: "lra-cluster"
        volumeMounts:
        - name: object-store
          mountPath: /var/lib/lra/tx-object-store
        - name: infinispan-data
          mountPath: /var/lib/lra/infinispan
```

See [HA-IMPLEMENTATION.md](HA-IMPLEMENTATION.md) for the full Kubernetes manifest.

## Testing

```bash
# Run HA unit tests
mvn test -pl coordinator-ha-infinispan

# 22 tests covering: cache operations, state replication,
# distributed locking, state transitions, concurrent access,
# node ID embedding, and reentrant lock safety
```

## Best Practices

1. **Use odd number of nodes** (3, 5, 7) to guarantee majority in any network split
2. **Design idempotent compensators** — recovery may call compensate multiple times
3. **Use REPL_SYNC** (default) for consistency; DIST_SYNC for larger clusters (>5 nodes)
4. **Set explicit node IDs** in production rather than relying on hostname fallback

## Further Reading

- [HA-IMPLEMENTATION.md](HA-IMPLEMENTATION.md) — Architecture details, design decisions, and known limitations
- [application-ha.properties.example](src/main/resources/application-ha.properties.example) — Annotated configuration example
