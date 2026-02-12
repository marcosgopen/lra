# Network Partition Handling in LRA HA

## Overview

Network partitions (split-brain scenarios) are handled automatically by **Infinispan's built-in partition handling**. We don't need custom code - just proper configuration.

## How Infinispan Handles Partitions

### Partition Detection

Infinispan uses **JGroups** to detect network partitions:
- JGroups continuously monitors cluster membership via heartbeats
- When nodes become unreachable, JGroups creates separate cluster views
- Infinispan detects the partition from the view change

### Partition Strategy: DENY_READ_WRITES

We configure all LRA caches with `DENY_READ_WRITES` strategy:

```java
.partitionHandling()
    .whenSplit(PartitionHandling.DENY_READ_WRITES)
    .mergePolicy(MergePolicy.PREFERRED_ALWAYS)
```

**Behavior:**

| Partition | Availability Mode | Read Operations | Write Operations |
|-----------|------------------|-----------------|------------------|
| **Majority** (>50% of nodes) | `AVAILABLE` | ✅ Allowed | ✅ Allowed |
| **Minority** (≤50% of nodes) | `DEGRADED_MODE` | ❌ Throws exception | ❌ Throws exception |

### Why DENY_READ_WRITES?

For LRA coordinators, this is the **safest strategy**:

- **Prevents duplicate recovery**: Only majority partition performs recovery
- **Prevents split-brain**: Minority cannot modify LRA state
- **Prevents duplicate compensations**: Cannot close/cancel LRAs in minority
- **Fail-safe**: Better to reject operations than risk data corruption

## Network Partition Scenarios

### Scenario 1: Clean Split (2+1)

```
Initial State (3 nodes):
┌─────────┐  ┌─────────┐  ┌─────────┐
│ Node A  │──│ Node B  │──│ Node C  │
│(COORD)  │  │         │  │         │
└─────────┘  └─────────┘  └─────────┘
All: AVAILABLE, recovery on A

Network Partition:
┌─────────┐  ┌─────────┐     ║     ┌─────────┐
│ Node A  │──│ Node B  │     ║     │ Node C  │
│(COORD)  │  │         │     ║     │         │
│AVAILABLE│  │AVAILABLE│     ║     │DEGRADED │
└─────────┘  └─────────┘     ║     └─────────┘
   Majority (2/3)             ║     Minority (1/3)

   ✅ A & B continue           ❌ C denies all operations
   ✅ A still coordinator      ❌ C throws exceptions
   ✅ Recovery continues       ❌ No recovery
   ✅ New LRAs accepted        ❌ New LRAs rejected
```

**Client Impact:**
- Requests to A or B: **Success** ✅
- Requests to C: **503 Service Unavailable** ❌

**Recommendation:** Use load balancer health checks to route away from C

### Scenario 2: Even Split (2+2)

```
Initial State (4 nodes):
┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐
│ Node A  │──│ Node B  │──│ Node C  │──│ Node D  │
└─────────┘  └─────────┘  └─────────┘  └─────────┘

Network Partition (2+2):
┌─────────┐  ┌─────────┐     ║     ┌─────────┐  ┌─────────┐
│ Node A  │──│ Node B  │     ║     │ Node C  │──│ Node D  │
│DEGRADED │  │DEGRADED │     ║     │DEGRADED │  │DEGRADED │
└─────────┘  └─────────┘     ║     └─────────┘  └─────────┘
   No Majority (2/4)          ║     No Majority (2/4)

   ❌ Both sides DEGRADED
   ❌ All operations denied
   ❌ No recovery anywhere
```

**Client Impact:**
- All requests: **503 Service Unavailable** ❌

**Resolution:** Must restore connectivity or manually intervene

**Prevention:** Deploy **odd number of nodes** (3, 5, 7) to avoid 50/50 splits

### Scenario 3: Partition Heals

```
During Partition:
┌─────────┐  ┌─────────┐     ║     ┌─────────┐
│ Node A  │──│ Node B  │     ║     │ Node C  │
│AVAILABLE│  │AVAILABLE│     ║     │DEGRADED │
└─────────┘  └─────────┘     ║     └─────────┘

Network Restored:
┌─────────┐  ┌─────────┐  ┌─────────┐
│ Node A  │──│ Node B  │──│ Node C  │
│AVAILABLE│  │AVAILABLE│  │AVAILABLE│
└─────────┘  └─────────┘  └─────────┘

Infinispan automatically:
1. Detects merge (JGroups MergeView)
2. Resolves conflicts (PREFERRED_ALWAYS policy)
3. Returns all nodes to AVAILABLE
4. Resumes normal operations
```

## How LRA Coordinator Protects Against Partitions

### 1. **Start LRA** - Protected

```java
public LongRunningAction startLRA(...) {
    // Check cache availability
    if (haEnabled && !infinispanStore.isAvailable()) {
        throw ServiceUnavailableException("Coordinator in minority partition");
    }
    // ... create LRA
}
```

**Result:** Minority partition cannot create new LRAs

### 2. **Get LRA** - Protected

```java
public LongRunningAction getTransaction(URI lraId) {
    // In HA mode, load from Infinispan
    if (haEnabled && !infinispanStore.isAvailable()) {
        throw ServiceUnavailableException("Coordinator in minority partition");
    }
    // ... load LRA
}
```

**Result:** Minority partition cannot access LRA state

### 3. **Close/Cancel LRA** - Protected

Inherits protection from `getTransaction()` - if you can't get it, you can't close it.

### 4. **Recovery** - Protected

```java
public void periodicWorkSecondPass() {
    // Only cluster coordinator performs recovery
    if (!isRecoveryLeader) return;

    // Only if cache is available (not in minority)
    if (!infinispanStore.isAvailable()) return;

    // Perform recovery
}
```

**Result:** Only majority partition performs recovery

### 5. **Distributed Locks** - Protected

Infinispan clustered locks are **partition-aware**:
- Locks acquired in majority partition work normally
- Locks attempted in minority partition fail
- Locks held by failed nodes are automatically released

## Merge Conflict Resolution

### Merge Policy: PREFERRED_ALWAYS

When partitions merge, Infinispan uses `PREFERRED_ALWAYS` policy:

```
Partition A (majority):
LRA-123 → Status: Closed, FinishTime: 14:30:00

Partition B (minority - was degraded):
LRA-123 → Status: Active, FinishTime: null

After Merge:
LRA-123 → Status: Closed, FinishTime: 14:30:00
(Majority version wins)
```

**Why this works for LRA:**
- Minority partition couldn't modify state (DEGRADED_MODE)
- Majority partition has authoritative state
- No actual conflicts to resolve

**Edge case:** If both partitions were equal size (no majority):
- Both were DEGRADED
- Neither could modify state
- No conflicts when merged

## Monitoring Partition Status

### Check Cache Availability

```java
AvailabilityMode mode = infinispanStore.getAvailabilityMode();

switch (mode) {
    case AVAILABLE:
        // Normal operation
        break;
    case DEGRADED_MODE:
        // In minority partition - deny operations
        break;
}
```

### Health Check Endpoint

Expose cache availability in health check:

```java
@Path("/health")
public class HealthCheck {
    @GET
    public Response health() {
        if (infinispanStore.isAvailable()) {
            return Response.ok("AVAILABLE").build();
        } else {
            return Response.status(503)
                .entity("DEGRADED - minority partition")
                .build();
        }
    }
}
```

**Kubernetes/Load Balancer:** Configure readiness probe to check this endpoint

## Best Practices

### 1. **Use Odd Number of Nodes**

✅ **Good:** 3, 5, 7 nodes
- Guarantees majority in any clean split
- 3-node cluster: Tolerates 1 failure
- 5-node cluster: Tolerates 2 failures

❌ **Bad:** 2, 4, 6 nodes
- Risk of 50/50 split with no majority
- All nodes enter DEGRADED_MODE

### 2. **Configure Load Balancer Health Checks**

```yaml
# Kubernetes readiness probe
readinessProbe:
  httpGet:
    path: /health
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
  failureThreshold: 3
```

Routes traffic away from nodes in DEGRADED_MODE

### 3. **Monitor Availability Mode**

Log and alert when caches enter DEGRADED_MODE:

```java
if (mode == AvailabilityMode.DEGRADED_MODE) {
    logger.error("ALERT: Cache in DEGRADED_MODE - network partition detected!");
    // Send alert to monitoring system
}
```

### 4. **Set Appropriate Timeouts**

JGroups failure detection settings:

```xml
<FD_ALL timeout="10000"     <!-- Failure detection timeout -->
        interval="3000"      <!-- Check interval -->
        timeout_check_interval="2000"/>
```

Faster detection = quicker failover, but more false positives

### 5. **Plan for Even Splits**

If 50/50 split occurs (all nodes DEGRADED):

**Option 1:** Restore network connectivity
**Option 2:** Manual intervention - force one partition to majority

```java
// Emergency: Force this partition to be available
cache.getAdvancedCache()
    .getPartitionHandlingManager()
    .setAvailabilityMode(AvailabilityMode.AVAILABLE);
```

⚠️ **Warning:** Only do this if you're sure the other partition is dead!

## Testing Partition Handling

### Test 1: Simulate Network Partition

```bash
# Using firewall rules to block traffic between nodes
# On Node C, block traffic from A and B:
iptables -A INPUT -s <node-a-ip> -j DROP
iptables -A INPUT -s <node-b-ip> -j DROP

# Wait for JGroups to detect partition (10-30 seconds)

# Try to create LRA on Node C:
curl -X POST http://node-c:8080/lra-coordinator/start
# Expected: 503 Service Unavailable

# Try to create LRA on Node A (majority):
curl -X POST http://node-a:8080/lra-coordinator/start
# Expected: 200 OK
```

### Test 2: Verify Merge

```bash
# Restore network
iptables -F

# Wait for merge (10-30 seconds)

# All nodes should return to AVAILABLE
curl http://node-c:8080/health
# Expected: 200 OK - AVAILABLE
```

### Test 3: Verify Locks Release on Failure

```bash
# Acquire lock on Node A
# Kill Node A
# Try to acquire same lock on Node B
# Expected: Success (lock automatically released)
```

## Comparison: Custom vs Infinispan Partition Handling

| Feature | Custom Implementation | Infinispan Built-in |
|---------|----------------------|---------------------|
| Partition detection | Manual JGroups listener | ✅ Automatic |
| Majority quorum | Manual calculation | ✅ Automatic |
| Operation blocking | Manual checks everywhere | ✅ Automatic in cache |
| Lock release on failure | Manual tracking | ✅ Automatic |
| Merge detection | Manual merge view handling | ✅ Automatic |
| Conflict resolution | Manual implementation | ✅ Configurable policies |
| Testing | Complex to test | ✅ Well-tested in production |
| Code maintenance | High | ✅ Zero (configuration only) |

**Verdict:** Use Infinispan's built-in partition handling!

## Summary

Network partition handling is **fully automatic** with Infinispan:

✅ **Partition Detection** - JGroups monitors cluster membership
✅ **Majority Quorum** - Only majority partition stays AVAILABLE
✅ **Minority Protection** - DEGRADED_MODE denies all operations
✅ **Automatic Merge** - Restores availability when network heals
✅ **Conflict Resolution** - PREFERRED_ALWAYS policy (majority wins)
✅ **Lock Safety** - Distributed locks are partition-aware

**You just need to:**
1. Configure `DENY_READ_WRITES` in cache configuration ✅ (already done)
2. Check `isAvailable()` before critical operations ✅ (already done)
3. Use odd number of nodes in production ⚠️ (deployment consideration)
4. Configure health checks for load balancer ⚠️ (deployment consideration)

**No custom partition handling code needed!**
