# LRA Coordinator High Availability Implementation

## Overview

This implementation enables multiple LRA coordinators to work together in a cluster, addressing the key requirements:

1. **Shared Object Store**: Different coordinators can share an object store by embedding node ID in LRA IDs
2. **Any Coordinator Can Manage Any LRA**: Distributed locking ensures safe concurrent access
3. **Single Recovery Manager**: JGroups coordinator election ensures only one node performs recovery
4. **Cloud-Native**: Designed for Kubernetes/OpenShift with automatic failover

## Architecture

### Components

1. **InfinispanStore** - Distributed LRA state storage
   - Three replicated caches: active, recovering, failed
   - Automatic state replication across cluster
   - Any coordinator can access any LRA state

2. **DistributedLockManager** - Infinispan clustered locks
   - Prevents concurrent modifications to same LRA
   - Uses Infinispan's lock API (backed by JGroups)
   - Automatic lock release on node failure

3. **ClusterCoordinator** - JGroups coordinator election
   - First node in cluster view becomes coordinator
   - Automatic failover when coordinator fails
   - Only coordinator performs recovery scans

4. **LRAState** - Serializable LRA representation
   - Contains all LRA state for distributed storage
   - Includes node ID for tracking LRA origin
   - Used for Infinispan cache entries

#### Node ID Embedding

LRA IDs are formatted as:
- **Single-instance mode**: `http://host:port/lra-coordinator/{uid}`
- **HA mode**: `http://host:port/lra-coordinator/{nodeId}/{uid}`

This allows:
- Multiple coordinators to use the same object store without conflicts
- Easy identification of which node created an LRA
- Single recovery manager to recover LRAs from all nodes

**UID extraction is not affected** by the embedded node ID. `LRAConstants.getLRAUid()` extracts the last path segment (`path.substring(path.lastIndexOf('/') + 1)` for URI input, and regex `.*/([^/?]+).*` for String input), so the UID is correctly extracted regardless of whether a node ID prefix is present.

**Migration note**: Switching a deployment between single-instance and HA mode changes the LRA URI format. Any in-flight LRAs created under the previous mode will have URIs that don't match the new format. Ensure all LRAs are completed before changing modes, or plan for a rolling migration.

## How It Works

### Starting an LRA

1. Client calls `POST /lra-coordinator/start`
2. Coordinator creates LRA with node ID embedded in URI
3. LRA state is saved to:
   - Local ObjectStore (traditional Narayana persistence)
   - Infinispan active cache (replicated across cluster)
4. Any coordinator can now access this LRA

**Dual-write ordering**: The Infinispan write happens in `LongRunningAction.save_state()` *after* all ObjectStore serialization has completed successfully, ensuring the distributed store never contains incomplete state.

### Accessing an LRA

1. Client calls any coordinator with LRA ID
2. Coordinator checks local memory first
3. If not found, loads from Infinispan cache
4. Acquires distributed lock before modifications
5. Updates are saved to both ObjectStore and Infinispan

### State Transitions and Cache Management

When an LRA changes status (e.g. Active → Closing), `InfinispanStore.saveLRA()` routes the entry to the appropriate cache based on the new status and **removes it from all other caches**. This prevents stale entries from shadowing the current state, since `loadLRA()` checks caches in a fixed order (active → recovering → failed).

The `moveToRecovering()` and `moveToFailed()` methods delegate to this same mechanism, ensuring a single code path for cache routing and cleanup.

### Ending an LRA

1. Any coordinator can close/cancel any LRA
2. Distributed lock prevents conflicts
3. State transitions are persisted to the correct cache with stale entries cleaned up
4. Completed LRAs are removed from caches

### Recovery

1. Only the JGroups cluster coordinator performs recovery
2. Recovery scans both ObjectStore and Infinispan
3. Uses distributed locks to avoid conflicts
4. If coordinator fails, new coordinator takes over automatically

**Recovery leader election**: The recovery leader is determined as follows:
- If a cluster coordinator is available, `isRecoveryLeader` is seeded from its current state and updated via view change callbacks
- If no cluster coordinator is available (single-node HA, JGroups not configured), the node assumes recovery leadership to ensure recovery always runs

**Timeout handling**: When a coordinator dies before its local timeout fires, timed-out LRAs remain in the active cache. The recovery leader's periodic scan detects these expired entries and moves them to the recovering cache. The normal recovery path then loads them via `restore_state()`, which detects the expired `finishTime` and schedules cancellation. This avoids duplicating cancellation logic in the timeout check.

**ActionStatus mapping**: When recovering an LRA from the distributed store, the `LRAStatus` is mapped to the correct Arjuna `ActionStatus` (e.g. Closing → COMMITTING, Cancelling → ABORTING, FailedTo* → H\_HAZARD) so that `RecoveringLRA.tryReplayPhase2()` makes the correct replay decision.

### Coordinator Failover

1. JGroups detects node failure
2. New cluster view is computed
3. First surviving node becomes new coordinator
4. New coordinator immediately starts recovery scan
5. In-flight LRAs continue on other nodes

## Configuration

### Enable HA Mode

```properties
lra.coordinator.ha.enabled=true
lra.coordinator.cluster.name=lra-cluster
lra.coordinator.node.id=lra-coord-1
```

### JGroups Configuration

For production, use a custom JGroups configuration:

```properties
lra.coordinator.jgroups.config=jgroups-prod.xml
```

### Kubernetes/OpenShift

```properties
# Uses pod name as node ID
# HOSTNAME environment variable is automatically set by K8s

# Use KUBE_PING for discovery
lra.coordinator.jgroups.config=jgroups-kubernetes.xml
```

## Kubernetes Deployment Example

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: lra-coordinator
spec:
  serviceName: lra-coordinator
  replicas: 3
  selector:
    matchLabels:
      app: lra-coordinator
  template:
    metadata:
      labels:
        app: lra-coordinator
    spec:
      containers:
      - name: coordinator
        image: lra-coordinator:latest
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
  volumeClaimTemplates:
  - metadata:
      name: object-store
    spec:
      accessModes: ["ReadWriteOnce"]
      resources:
        requests:
          storage: 10Gi
  - metadata:
      name: infinispan-data
    spec:
      accessModes: ["ReadWriteOnce"]
      resources:
        requests:
          storage: 10Gi
---
apiVersion: v1
kind: Service
metadata:
  name: lra-coordinator
spec:
  clusterIP: None  # Headless service for JGroups discovery
  selector:
    app: lra-coordinator
  ports:
  - port: 8080
    name: http
  - port: 7800
    name: jgroups
```

## Network Partition Handling

Network partitions are **automatically handled by Infinispan** using the `DENY_READ_WRITES` strategy:

- **Majority partition** (>50% of nodes): Continues normal operation
- **Minority partition** (≤50% of nodes): Enters DEGRADED_MODE, denies all operations
- **Automatic merge**: When partition heals, Infinispan automatically merges state

**Configuration in InfinispanConfiguration.java:**
```java
.partitionHandling()
    .whenSplit(PartitionHandling.DENY_READ_WRITES)
    .mergePolicy(MergePolicy.PREFERRED_ALWAYS)
```

**Best Practice:** Deploy odd number of nodes (3, 5, 7) to avoid 50/50 splits

## Benefits

### High Availability
- No single point of failure
- Automatic coordinator failover
- LRAs survive node failures
- Partition tolerance with automatic detection

### Scalability
- Horizontal scaling by adding nodes
- Load balancing across coordinators
- Shared state enables work distribution

### Cloud-Native
- Stateless coordinator instances
- Works with Kubernetes pod scaling
- Service mesh compatible

### Compatibility
- Backward compatible with single-instance mode
- No changes required to LRA clients
- Existing LRAs continue to work

## Comparison to Previous Approach

### Before HA
- Single coordinator per object store
- Manual failover required
- Lost in-flight LRAs on crash

### After HA
- Multiple coordinators share object store
- Automatic failover
- In-flight LRAs recovered automatically
- Any coordinator can manage any LRA

## Testing

To test HA mode:

1. Start multiple coordinator instances with same cluster name
2. Create LRAs on different coordinators
3. Stop the coordinator that created an LRA
4. Close/cancel the LRA from a different coordinator
5. Verify recovery continues after coordinator failure

## Performance Considerations

### Tradeoffs
- Distributed locks add latency (typically <10ms)
- Infinispan replication adds network overhead
- Recovery is centralized to one coordinator

### Optimizations
- Local cache checks before Infinispan lookup
- Distributed locks only for modifications
- Infinispan uses efficient replication (JGroups)

## Design Notes

### Cache Consistency

Multi-cache operations (e.g. remove from active + put into recovering) are not wrapped in Infinispan transactions. If a crash occurs between the remove and the put, the LRA entry could be temporarily lost. This is mitigated by:
- Distributed locks preventing concurrent modifications
- The recovery module re-processing any LRA that goes missing
- `REPL_SYNC` ensuring each individual cache operation is replicated before returning

Using Infinispan transactions would make these operations atomic but adds latency (lock acquisition, 2PC across replicas) and complexity. The current approach is a pragmatic trade-off given the recovery safety net.

### Serialization

Infinispan uses ProtoStream (the default and recommended marshaller for Infinispan 16.x) to serialize `LRAState` objects. The `LRAState` class is annotated with `@ProtoFactory` and `@ProtoField`, and an `@AutoProtoSchemaBuilder` interface (`LRASchemaInitializer`) generates the schema and marshaller at compile time. Non-proto types (`URI`, `LRAStatus`, `LocalDateTime`) are converted to/from `String` in the proto-annotated constructor and getters.

### Known Limitations

- `lockTransaction()` / `tryLockTransaction()` / `tryTimedLockTransaction()` in `LRAService` are `synchronized` on the service instance, meaning only one distributed lock acquisition can be in progress at a time. This creates a bottleneck under high concurrency.
- The `InfinispanClusterCoordinator` scheduler uses a non-daemon thread (`Executors.newScheduledThreadPool(1)`), which can prevent JVM exit on unclean shutdown without `@PreDestroy`.

## Future Enhancements

1. **Partitioning**: Shard LRAs across coordinators by ID hash
2. **Read Replicas**: Allow read-only access without locks
3. **Metrics**: Expose cluster health and performance metrics
4. **Admin API**: Manage cluster membership and failover
