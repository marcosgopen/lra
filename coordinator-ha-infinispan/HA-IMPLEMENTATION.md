# LRA Coordinator High Availability Implementation

## Overview

This module (`lra-coordinator-ha-infinispan`) provides High Availability for the LRA Coordinator using Infinispan and JGroups clustering. It is a **separate Maven module** with no compile-time dependency from the core coordinator -- projects opt in by adding this module to their classpath and setting `lra.coordinator.ha.enabled=true`.

For integration instructions, see [README-HA.md](README-HA.md).

### Requirements

1. **Shared Object Store**: All coordinators share state via Infinispan-backed ObjectStore
2. **Any Coordinator Can Manage Any LRA**: Replicated state means any node can read/write any LRA
3. **Single Recovery Manager**: JGroups coordinator election ensures only one node performs recovery
4. **Cloud-Native**: Designed for Kubernetes/OpenShift with automatic failover

## Architecture

### Design Principle

LRA state persistence flows through Narayana's **standard ObjectStore path**. In HA mode, the ObjectStore backend is swapped from the default filesystem to `InfinispanSlots` -- a `BackingSlots` implementation that writes to a replicated Infinispan cache. No custom persistence code in the LRA coordinator.

### Module Structure

```
coordinator/                         # Core coordinator (no Infinispan dependency)
  internal/
    ClusterCoordinationService.java  # Interface for leader election
    LRARecoveryModule.java           # Standard ObjectStore recovery + leader guard

coordinator-ha-infinispan/           # HA module (this module)
  infinispan/
    InfinispanClusterCoordinator.java  # Leader election via JGroups view
    InfinispanConfiguration.java       # CDI producer for cache manager + coordinator
    HAObjectStoreConfiguration.java    # Wires InfinispanSlots into ObjectStore
```

### Components

1. **HAObjectStoreConfiguration** -- ObjectStore wiring
   - Detects `InfinispanSlots` on classpath via `Class.forName()`
   - Configures `ObjectStoreEnvironmentBean.objectStoreType` to `SlotStoreAdaptor`
   - Configures `InfinispanSlots` as the `BackingSlots` implementation
   - Creates replicated `lra-objectstore` cache with `DENY_READ_WRITES` partition handling
   - Falls back to filesystem ObjectStore if `InfinispanSlots` not available

2. **InfinispanClusterCoordinator** -- JGroups coordinator election
   - First node in cluster view becomes coordinator
   - Automatic failover when coordinator fails
   - Only coordinator performs recovery scans
   - Listens to `@ViewChanged` events for cluster membership changes
   - Fallback scheduler checks coordinator status every 10 seconds

3. **InfinispanConfiguration** -- CDI producer
   - WildFly subsystem mode: JNDI lookup at `java:jboss/infinispan/container/lra`
   - Embedded mode: standalone/Quarkus use self-created `DefaultCacheManager`
   - Triggers `HAObjectStoreConfiguration.configure()` on startup

### Node ID Embedding

LRA IDs are formatted as:
- **Single-instance mode**: `http://host:port/lra-coordinator/{uid}`
- **HA mode**: `http://host:port/lra-coordinator/{nodeId}/{uid}`

This allows:
- Multiple coordinators to use the same ObjectStore without UID collisions
- Easy identification of which node created an LRA
- Single recovery manager to recover LRAs from all nodes

**UID extraction is not affected** by the embedded node ID. `LRAConstants.getLRAUid()` extracts the last path segment, so the UID is correctly extracted regardless of whether a node ID prefix is present.

## How It Works

### Write Path

```
LongRunningAction.deactivate()
  -> save_state(os, ot)                           # serialize LRA state to buffer
  -> write_committed(Uid, type, bytes)             # ObjectStore API
    -> SlotStoreAdaptor.write_committed()
      -> SlotStore.write(key, bytes)
        -> InfinispanSlots.write(slot, data)
          -> cache.put(slots[slot], data)           # replicated to all nodes
```

Single storage path. No dual-write. No custom Infinispan persistence code.

### Read Path (Recovery)

```
LRARecoveryModule.periodicWorkSecondPass()
  -> recoverTransactionsFromObjectStore()           # standard Arjuna recovery
    -> allObjUids(LRA_TYPE)                         # scan all LRA entries
      -> SlotStoreAdaptor -> SlotStore.getMatchingKeys()
        -> InfinispanSlots -> cache iteration        # all nodes see same data
    -> for each Uid: read_committed(Uid, type)
      -> deserialize -> check status -> process
```

The existing `recoverTransactionsFromObjectStore()` code path works unchanged. Since the ObjectStore backend is Infinispan, all coordinators see the same data.

### Coordinator Failover

1. JGroups detects node failure via `@ViewChanged` events
2. New cluster view computed; first surviving node becomes coordinator
3. `onBecameCoordinator()` listener triggers immediate recovery scan
4. In-flight LRAs continue on other nodes (state is in Infinispan)

## Configuration

```properties
lra.coordinator.ha.enabled=true
lra.coordinator.cluster.name=lra-cluster
lra.coordinator.node.id=lra-coord-1
lra.coordinator.jgroups.config=jgroups-tcp.xml
lra.coordinator.jgroups.bind_addr=0.0.0.0
lra.coordinator.infinispan.cache.mode=REPL_SYNC
lra.coordinator.slots.max=10000
```

## Network Partition Handling

Handled **automatically by Infinispan** using the `DENY_READ_WRITES` strategy:

- **Majority partition** (>50% of nodes): Continues normal operation
- **Minority partition** (<=50% of nodes): Enters DEGRADED_MODE, denies all operations
- **Partition healing**: Infinispan merges state automatically via `PREFERRED_ALWAYS` policy

**Best Practice:** Deploy odd number of nodes (3, 5, 7) to avoid 50/50 splits.

## Testing

### Unit Tests

```bash
mvn test -pl coordinator-ha-infinispan
```

15 tests covering coordinator election, cluster failover, listener notifications, initialization lifecycle, and shutdown behavior.

### Component Tests

```bash
mvn test -pl test/ha
```

10 tests covering node ID embedding in LRA URIs, HA mode detection, and service initialization.

## Dependencies

The `InfinispanSlots` `BackingSlots` implementation comes from Narayana core ([PR #2537](https://github.com/jbosstm/narayana/pull/2537)). `HAObjectStoreConfiguration` detects its availability at runtime via classpath check and falls back to the default filesystem ObjectStore if not present.

## Future Enhancements

1. **Metrics**: Expose cluster health and replication metrics
2. **Read Replicas**: Allow read-only status queries without full cluster membership
3. **Admin API**: Manage cluster membership and trigger manual failover
