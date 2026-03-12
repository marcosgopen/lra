# LRA Coordinator HA - Infinispan Module

## Overview

This module provides **High Availability** for the LRA Coordinator using Infinispan and JGroups clustering. It is a **separate, optional module** -- the core coordinator has zero dependency on Infinispan. Projects that do not need HA simply omit this module.

In HA mode, the Narayana ObjectStore backend is replaced with an Infinispan-backed implementation (`InfinispanSlots` via Narayana's `BackingSlots` / `SlotStore` abstraction). All LRA state persistence flows through Arjuna's standard `deactivate()` -> `save_state()` -> `write_committed()` path -- the ObjectStore backend is simply swapped from filesystem to a replicated Infinispan cache.

## Integration Guide

### Step 1: Add the Maven Dependency

```xml
<dependency>
  <groupId>org.jboss.narayana.lra</groupId>
  <artifactId>lra-coordinator-ha-infinispan</artifactId>
  <version>${version.lra}</version>
</dependency>
```

### Step 2: Enable HA Mode

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

## How It Works

When both the dependency and configuration are in place:

1. CDI discovers the `@ApplicationScoped` beans in this module (`InfinispanConfiguration`, `InfinispanClusterCoordinator`)
2. `InfinispanConfiguration` initializes the Infinispan cache manager (WildFly JNDI or embedded mode)
3. `HAObjectStoreConfiguration` wires `InfinispanSlots` as the ObjectStore backend (if available on classpath)
4. All LRA state persistence flows through the replicated Infinispan cache
5. `LRARecoveryModule` looks up `ClusterCoordinationService` via CDI for recovery leader election
6. JGroups coordinator election ensures only one node performs recovery

### When HA Is Disabled

- If this module is **not on the classpath**, CDI never sees the Infinispan beans -- zero overhead
- If this module is on the classpath but `lra.coordinator.ha.enabled != true`, initialization is skipped

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
         +---+----+      +---+----+      +---+----+
         |SlotStore|     |SlotStore|     |SlotStore|
         | Adaptor |     | Adaptor |     | Adaptor |
         +---+----+      +---+----+      +---+----+
             |               |                |
             +-------+-------+--------+-------+
                     |
            +--------v---------+
            |  Infinispan Cache |
            |   (replicated)    |
            |  lra-objectstore  |
            +------------------+
         ---- JGroups transport ----
```

## Components

| Component | Class | Purpose |
|-----------|-------|---------|
| ObjectStore Wiring | `HAObjectStoreConfiguration` | Configures `SlotStoreAdaptor` + `InfinispanSlots` as ObjectStore backend |
| Leader Election | `InfinispanClusterCoordinator` | JGroups view-based coordinator election for recovery |
| CDI Configuration | `InfinispanConfiguration` | Cache manager producer (WildFly JNDI or embedded) |

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `lra.coordinator.ha.enabled` | `false` | Master switch for HA mode |
| `lra.coordinator.cluster.name` | `lra-cluster` | JGroups cluster name |
| `lra.coordinator.node.id` | `$HOSTNAME` | Unique node identifier |
| `lra.coordinator.jgroups.config` | (default UDP) | Path to JGroups XML config |
| `lra.coordinator.jgroups.bind_addr` | `127.0.0.1` | JGroups bind address |
| `lra.coordinator.infinispan.cache.mode` | `REPL_SYNC` | Cache replication mode |
| `lra.coordinator.slots.max` | `10000` | Maximum concurrent LRAs (SlotStore slot count) |

## Kubernetes Deployment

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: lra-coordinator
spec:
  serviceName: lra-coordinator
  replicas: 3  # Use odd number for proper quorum
  template:
    spec:
      containers:
      - name: coordinator
        env:
        - name: lra.coordinator.ha.enabled
          value: "true"
        - name: lra.coordinator.cluster.name
          value: "lra-cluster"
        - name: lra.coordinator.node.id
          valueFrom:
            fieldRef:
              fieldPath: metadata.name
```

## Testing

```bash
# Run unit tests (15 tests: coordinator election, failover, listeners)
mvn test -pl coordinator-ha-infinispan

# Run component tests (10 tests: node ID embedding, HA mode detection)
mvn test -pl test/ha
```

## Best Practices

1. **Use odd number of nodes** (3, 5, 7) to guarantee majority in any network split
2. **Design idempotent compensators** -- SAGA recovery may call compensate/complete multiple times
3. **Use REPL_SYNC** (default) for consistency; DIST_SYNC for larger clusters (>5 nodes)
4. **Set explicit node IDs** in production rather than relying on hostname fallback
5. **Configure `lra.coordinator.slots.max`** based on expected max concurrent LRAs
