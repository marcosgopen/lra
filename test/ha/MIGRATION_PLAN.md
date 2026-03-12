# Migration Plan: Infinispan + JGroups to JGroups-Raft ReplicatedStateMachine

## Executive Summary

This document outlines the migration strategy for the LRA (Long Running Actions) coordinator's High Availability implementation from **Infinispan distributed caches with JGroups** to **JGroups-Raft ReplicatedStateMachine**.

**Current State**: Infinispan 16.1.0 with JGroups 5.5.2 for eventual consistency replication  
**Target State**: JGroups-Raft ReplicatedStateMachine for strong consistency via RAFT consensus  
**Estimated Effort**: 3-4 weeks (development + testing)  
**Risk Level**: Medium-High (architectural change affecting data consistency)

---

## 1. Current Architecture Analysis

### 1.1 Existing Components

**Module**: `lra-coordinator-ha-infinispan` (version 1.1.1.Final-SNAPSHOT)

**Key Classes**:
- `InfinispanStore` - Manages LRA state persistence to Infinispan caches
- `InfinispanClusterCoordinator` - Handles cluster coordination and leadership

**Infinispan Caches** (configured via `configure-lra-caches.cli`):
```
/subsystem=infinispan/cache-container=lra
├── lra-active (replicated-cache) - Active LRAs
├── lra-recovering (replicated-cache) - LRAs in recovery
└── lra-failed (replicated-cache) - Failed LRAs
```

**Cache Configuration**:
- Replication mode: SYNC (synchronous)
- Partition handling: DENY_READ_WRITES (split-brain protection)
- Transport: JGroups (already present)

### 1.2 Current Consistency Model

- **Type**: Eventual consistency with synchronous replication
- **Conflict Resolution**: Last-write-wins
- **Partition Handling**: Deny operations during network splits
- **Ordering**: Per-key ordering, no global order guarantee

### 1.3 Dependencies

```xml
<dependency>
    <groupId>org.infinispan</groupId>
    <artifactId>infinispan-core</artifactId>
    <version>16.1.0</version>
</dependency>
<dependency>
    <groupId>org.jgroups</groupId>
    <artifactId>jgroups</artifactId>
    <version>5.5.2.Final</version>
</dependency>
```

---

## 2. Target Architecture: JGroups-Raft ReplicatedStateMachine

### 2.1 JGroups-Raft Overview

**Repository**: https://github.com/jgroups-extras/jgroups-raft

**Key Features**:
- **Strong Consistency**: RAFT consensus algorithm ensures linearizability
- **Leader Election**: Automatic leader election with majority quorum
- **Log-Based Replication**: All operations go through replicated log
- **Snapshot Support**: Log compaction to prevent unbounded growth
- **Fault Tolerance**: Tolerates (N-1)/2 failures in N-node cluster

### 2.2 ReplicatedStateMachine API

**Core Methods**:
```java
// Write operations (go through RAFT consensus)
V put(K key, V val)           // Returns previous value after commit
V remove(K key)               // Returns removed value after commit

// Read operations
V get(K key)                  // Configurable: dirty reads or linearizable

// Configuration
timeout(long timeout)         // Set replication timeout
allowDirtyReads(boolean f)    // Enable/disable local reads

// State machine callbacks (invoked after consensus)
byte[] apply(byte[] data, int offset, int length, boolean serialize_response)
void readContentFrom(DataInput in)
void writeContentTo(DataOutput out)

// Monitoring
long logSize()
String dumpLog()
```

### 2.3 Consistency Guarantees

| Aspect | JGroups-Raft | Current Infinispan |
|--------|--------------|-------------------|
| Write Consistency | Strong (majority quorum) | Eventual (sync replication) |
| Read Consistency | Linearizable (or dirty) | Local/Remote configurable |
| Ordering | Total order (all operations) | Per-key ordering |
| Split-Brain | Prevented by quorum | Configurable partition handling |
| Latency | Higher (consensus overhead) | Lower (direct replication) |

---

## 3. Migration Strategy

### 3.1 Phased Approach

**Phase 1: Preparation & Design** (Week 1)
- Create new module `lra-coordinator-ha-raft`
- Design state machine implementation
- Define serialization format
- Plan backward compatibility strategy

**Phase 2: Implementation** (Week 2)
- Implement `RaftStore` (replaces `InfinispanStore`)
- Implement `RaftClusterCoordinator` (replaces `InfinispanClusterCoordinator`)
- Create state machine for LRA operations
- Implement snapshot mechanism

**Phase 3: Testing** (Week 3)
- Unit tests for RAFT store
- Integration tests with WildFly cluster
- Failover and recovery testing
- Performance benchmarking

**Phase 4: Migration & Deployment** (Week 4)
- Create migration guide
- Update configuration scripts
- Gradual rollout strategy
- Rollback procedures

### 3.2 Backward Compatibility

**Option A: Dual-Mode Support** (Recommended)
- Keep both implementations in parallel
- Use system property to select: `lra.coordinator.ha.mode=raft|infinispan`
- Allows gradual migration and easy rollback

**Option B: Clean Break**
- Remove Infinispan implementation entirely
- Requires coordinated upgrade across all nodes
- Higher risk but simpler codebase

---

## 4. Detailed Implementation Plan

### 4.1 New Module Structure

```
lra-coordinator-ha-raft/
├── pom.xml
└── src/main/java/io/narayana/lra/coordinator/raft/
    ├── RaftStore.java                    // Main store implementation
    ├── RaftClusterCoordinator.java       // Cluster coordination
    ├── LraStateMachine.java              // RAFT state machine
    ├── LraOperation.java                 // Operation types (PUT/REMOVE/GET)
    ├── LraSnapshot.java                  // Snapshot support
    └── RaftConfiguration.java            // Configuration management
```

### 4.2 Core Classes Design

#### 4.2.1 RaftStore

```java
public class RaftStore implements LRAStore {
    private ReplicatedStateMachine<String, LRAData> stateMachine;
    private RaftHandle raftHandle;
    private boolean haEnabled;
    
    // Lifecycle
    public void initialize() {
        if (isHaEnabled()) {
            initializeRaft();
        }
    }
    
    private void initializeRaft() {
        // Create JChannel with RAFT protocol stack
        JChannel channel = new JChannel("raft.xml");
        
        // Create RAFT handle
        raftHandle = new RAFT()
            .raftId(getNodeId())
            .members(getClusterMembers())
            .stateMachine(new LraStateMachine());
            
        // Create replicated state machine
        stateMachine = new ReplicatedStateMachine<>(channel, raftHandle);
        stateMachine.timeout(getReplicationTimeout());
        stateMachine.allowDirtyReads(false); // Strong consistency
        
        channel.connect(getClusterName());
    }
    
    // LRA Operations
    @Override
    public void saveLRA(String lraId, LRAData data) {
        if (haEnabled) {
            stateMachine.put(lraId, data);
        } else {
            // Single-node mode
            localStore.put(lraId, data);
        }
    }
    
    @Override
    public LRAData loadLRA(String lraId) {
        if (haEnabled) {
            return stateMachine.get(lraId);
        } else {
            return localStore.get(lraId);
        }
    }
    
    @Override
    public void removeLRA(String lraId) {
        if (haEnabled) {
            stateMachine.remove(lraId);
        } else {
            localStore.remove(lraId);
        }
    }
    
    // Bulk operations for recovery
    @Override
    public Collection<LRAData> getAllActiveLRAs() {
        // Iterate over state machine entries
        // Filter by status == ACTIVE
    }
    
    // Snapshot support
    public void createSnapshot() {
        stateMachine.snapshot();
    }
}
```

#### 4.2.2 LraStateMachine

```java
public class LraStateMachine implements StateMachine {
    private final Map<String, LRAData> state = new ConcurrentHashMap<>();
    private final List<StateChangeListener> listeners = new CopyOnWriteArrayList<>();
    
    @Override
    public byte[] apply(byte[] data, int offset, int length, 
                       boolean serialize_response) throws Exception {
        // Deserialize operation
        LraOperation op = deserialize(data, offset, length);
        
        byte[] result = null;
        
        switch (op.getType()) {
            case PUT:
                LRAData previous = state.put(op.getKey(), op.getValue());
                notifyListeners(op.getKey(), op.getValue());
                result = serialize(previous);
                break;
                
            case REMOVE:
                LRAData removed = state.remove(op.getKey());
                notifyListeners(op.getKey(), null);
                result = serialize(removed);
                break;
                
            case GET:
                LRAData current = state.get(op.getKey());
                result = serialize(current);
                break;
        }
        
        return result;
    }
    
    @Override
    public void readContentFrom(DataInput in) throws Exception {
        // Read snapshot
        int size = in.readInt();
        state.clear();
        
        for (int i = 0; i < size; i++) {
            String key = in.readUTF();
            LRAData value = readLRAData(in);
            state.put(key, value);
        }
    }
    
    @Override
    public void writeContentTo(DataOutput out) throws Exception {
        // Write snapshot
        out.writeInt(state.size());
        
        for (Map.Entry<String, LRAData> entry : state.entrySet()) {
            out.writeUTF(entry.getKey());
            writeLRAData(out, entry.getValue());
        }
    }
    
    private void notifyListeners(String key, LRAData value) {
        for (StateChangeListener listener : listeners) {
            listener.onStateChange(key, value);
        }
    }
}
```

#### 4.2.3 RaftClusterCoordinator

```java
public class RaftClusterCoordinator implements ClusterCoordinator {
    private RaftHandle raftHandle;
    private JChannel channel;
    
    @Override
    public boolean isCoordinator() {
        // In RAFT, only the leader is the coordinator
        return raftHandle != null && raftHandle.isLeader();
    }
    
    @Override
    public String getCoordinatorId() {
        if (raftHandle != null) {
            Address leader = raftHandle.leader();
            return leader != null ? leader.toString() : null;
        }
        return null;
    }
    
    @Override
    public List<String> getClusterMembers() {
        if (channel != null) {
            return channel.getView().getMembers().stream()
                .map(Address::toString)
                .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
    
    @Override
    public void addMembershipListener(MembershipListener listener) {
        if (channel != null) {
            channel.addChannelListener(new ChannelListener() {
                @Override
                public void viewAccepted(View view) {
                    listener.onMembershipChange(
                        view.getMembers().stream()
                            .map(Address::toString)
                            .collect(Collectors.toList())
                    );
                }
            });
        }
    }
}
```

### 4.3 Configuration

#### 4.3.1 RAFT Protocol Stack (raft.xml)

```xml
<config xmlns="urn:org:jgroups"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="urn:org:jgroups http://www.jgroups.org/schema/jgroups.xsd">
    
    <!-- Transport -->
    <TCP bind_addr="${jgroups.bind.address:0.0.0.0}"
         bind_port="${jgroups.bind.port:7800}"
         thread_pool.min_threads="2"
         thread_pool.max_threads="10"/>
    
    <!-- Discovery -->
    <TCPPING initial_hosts="${jgroups.tcpping.initial_hosts:localhost[7800],localhost[7801],localhost[7802]}"
             port_range="3"/>
    
    <!-- Failure Detection -->
    <FD_ALL timeout="10000" interval="3000"/>
    <VERIFY_SUSPECT timeout="1500"/>
    
    <!-- RAFT Protocol -->
    <raft.ELECTION election_min_interval="100"
                   election_max_interval="500"
                   heartbeat_interval="50"/>
    
    <raft.RAFT members="${raft.members:A,B,C}"
               raft_id="${raft.id:A}"
               log_class="org.jgroups.protocols.raft.InMemoryLog"
               log_size="1000"
               snapshot_interval="100"/>
    
    <!-- Reliability -->
    <UNICAST3/>
    <NAKACK2/>
    
    <!-- State Transfer -->
    <STATE_TRANSFER/>
</config>
```

#### 4.3.2 System Properties

```properties
# RAFT Configuration
lra.coordinator.ha.mode=raft
lra.coordinator.raft.cluster.name=lra-cluster
lra.coordinator.raft.node.id=${HOSTNAME}
lra.coordinator.raft.members=node1,node2,node3
lra.coordinator.raft.replication.timeout=5000
lra.coordinator.raft.snapshot.interval=1000
lra.coordinator.raft.log.size=10000

# JGroups Configuration
jgroups.bind.address=0.0.0.0
jgroups.bind.port=7800
jgroups.tcpping.initial_hosts=node1[7800],node2[7800],node3[7800]
```

### 4.4 Maven Dependencies

```xml
<dependency>
    <groupId>org.jgroups</groupId>
    <artifactId>jgroups-raft</artifactId>
    <version>1.0.13.Final</version>
</dependency>
<dependency>
    <groupId>org.jgroups</groupId>
    <artifactId>jgroups</artifactId>
    <version>5.5.2.Final</version>
</dependency>
```

---

## 5. Data Migration Strategy

### 5.1 State Transfer Approaches

**Option A: Rolling Upgrade with Dual-Write**
1. Deploy new version with both Infinispan and RAFT support
2. Enable dual-write mode (write to both stores)
3. Background sync from Infinispan to RAFT
4. Switch reads to RAFT once sync complete
5. Disable Infinispan writes
6. Remove Infinispan in next release

**Option B: Cluster Restart with Export/Import**
1. Stop all coordinator nodes
2. Export LRA state from Infinispan to JSON/binary
3. Start nodes with RAFT configuration
4. Import state into RAFT store
5. Resume operations

**Option C: Zero-Downtime with Proxy**
1. Deploy RAFT cluster alongside Infinispan
2. Route new LRAs to RAFT
3. Keep existing LRAs in Infinispan until completion
4. Gradually drain Infinispan cluster
5. Decommission Infinispan once empty

### 5.2 Recommended Approach

**Hybrid: Rolling Upgrade + Graceful Drain** (Lowest Risk)

**Steps**:
1. **Preparation**
   - Deploy version with both implementations
   - Configure `lra.coordinator.ha.mode=infinispan` (default)
   
2. **Gradual Migration** (per node)
   - Stop node
   - Change config to `lra.coordinator.ha.mode=raft`
   - Start node (joins RAFT cluster)
   - Verify health
   - Repeat for next node

3. **State Synchronization**
   - New LRAs created in RAFT
   - Existing LRAs remain in Infinispan until completion
   - No cross-store synchronization needed

4. **Completion**
   - Monitor Infinispan for active LRAs
   - Once empty, remove Infinispan dependencies
   - Clean up configuration

---

## 6. Testing Strategy

### 6.1 Unit Tests

```java
@Test
void testRaftStorePutAndGet() {
    RaftStore store = new RaftStore();
    store.initialize();
    
    LRAData data = new LRAData("lra-123", LRAStatus.ACTIVE);
    store.saveLRA("lra-123", data);
    
    LRAData retrieved = store.loadLRA("lra-123");
    assertEquals(data, retrieved);
}

@Test
void testLeaderElection() {
    // Start 3 nodes
    RaftStore node1 = createNode("node1");
    RaftStore node2 = createNode("node2");
    RaftStore node3 = createNode("node3");
    
    // Wait for leader election
    await().atMost(10, SECONDS).until(() -> 
        node1.getCoordinator().isCoordinator() ||
        node2.getCoordinator().isCoordinator() ||
        node3.getCoordinator().isCoordinator()
    );
    
    // Verify only one leader
    int leaderCount = Stream.of(node1, node2, node3)
        .filter(n -> n.getCoordinator().isCoordinator())
        .count();
    assertEquals(1, leaderCount);
}
```

### 6.2 Integration Tests

**Existing Tests to Adapt**:
- `HAClusteringTest.java` - Basic HA functionality
- `DataConsistencyIT.java` - Data consistency verification
- `FailureScenarioIT.java` - Node failure handling
- `MultiNodeClusterIT.java` - Multi-node operations

**New Tests Required**:
```java
@Test
void testRaftConsensusUnderLoad() {
    // Create 100 concurrent LRAs
    // Verify all committed successfully
    // Check log consistency across nodes
}

@Test
void testLeaderFailover() {
    // Identify current leader
    // Kill leader node
    // Verify new leader elected
    // Verify operations continue
    // Verify no data loss
}

@Test
void testNetworkPartition() {
    // Create 5-node cluster
    // Partition into 3+2
    // Verify majority (3) continues
    // Verify minority (2) rejects writes
    // Heal partition
    // Verify reconciliation
}

@Test
void testSnapshotAndRestore() {
    // Create 1000 LRAs
    // Trigger snapshot
    // Restart node
    // Verify state restored from snapshot
    // Verify log replay from snapshot point
}
```

### 6.3 Performance Benchmarks

**Metrics to Compare**:
- Write latency (p50, p95, p99)
- Read latency (dirty vs linearizable)
- Throughput (ops/sec)
- Leader election time
- Failover time
- Memory usage
- Network bandwidth

**Test Scenarios**:
1. **Baseline**: Single-node performance
2. **3-Node Cluster**: Normal operations
3. **5-Node Cluster**: Larger cluster overhead
4. **Under Load**: 1000 concurrent LRAs
5. **Failure Recovery**: Leader crash during load
6. **Network Latency**: Simulated WAN delays

---

## 7. Operational Considerations

### 7.1 Cluster Sizing

**Minimum**: 3 nodes (tolerates 1 failure)  
**Recommended**: 5 nodes (tolerates 2 failures)  
**Maximum**: 7-9 nodes (diminishing returns due to consensus overhead)

**Quorum Requirements**:
- 3 nodes: Need 2 for quorum (50% + 1)
- 5 nodes: Need 3 for quorum
- 7 nodes: Need 4 for quorum

### 7.2 Monitoring

**Key Metrics**:
```java
// RAFT-specific metrics
raft.leader.id                    // Current leader
raft.log.size                     // Log entries count
raft.log.commit_index             // Last committed index
raft.snapshot.last_index          // Last snapshot index
raft.election.count               // Number of elections
raft.heartbeat.failures           // Missed heartbeats

// Performance metrics
raft.operation.latency            // Write latency
raft.replication.lag              // Follower lag
raft.state_machine.apply_time     // Apply duration
```

**Health Checks**:
```bash
# Check cluster status
curl http://localhost:8080/lra-coordinator/health/raft

# Expected response
{
  "status": "UP",
  "raft": {
    "role": "LEADER",
    "term": 42,
    "commitIndex": 1523,
    "logSize": 1600,
    "members": ["node1", "node2", "node3"],
    "quorum": true
  }
}
```

### 7.3 Troubleshooting

**Common Issues**:

1. **Split-Brain Prevention**
   - Symptom: Cluster partitioned, no writes accepted
   - Cause: Network partition, no majority quorum
   - Solution: Heal network, ensure odd number of nodes

2. **Leader Election Storms**
   - Symptom: Frequent leader changes
   - Cause: Network instability, timeouts too aggressive
   - Solution: Increase election timeout, check network

3. **Log Growth**
   - Symptom: Memory usage increasing
   - Cause: Snapshots not triggering
   - Solution: Reduce snapshot interval, increase frequency

4. **Slow Writes**
   - Symptom: High write latency
   - Cause: Consensus overhead, slow followers
   - Solution: Check network latency, optimize state machine

### 7.4 Backup and Recovery

**Snapshot-Based Backup**:
```bash
# Trigger manual snapshot
curl -X POST http://localhost:8080/lra-coordinator/admin/raft/snapshot

# Backup snapshot file
cp /var/lib/lra/raft/snapshot-*.dat /backup/

# Restore from snapshot
# 1. Stop node
# 2. Copy snapshot to data directory
# 3. Start node (will replay log from snapshot)
```

**Log-Based Recovery**:
- RAFT log is durable (persisted to disk)
- On restart, node replays log from last snapshot
- Followers catch up from leader if behind

---

## 8. Rollback Plan

### 8.1 Rollback Triggers

- Critical bugs in RAFT implementation
- Unacceptable performance degradation (>2x latency increase)
- Data consistency issues
- Cluster instability (frequent leader elections)

### 8.2 Rollback Procedure

**If Dual-Mode Deployed**:
1. Change system property: `lra.coordinator.ha.mode=infinispan`
2. Restart nodes one by one
3. Verify Infinispan cluster health
4. Monitor for issues

**If Clean Break**:
1. Stop all nodes
2. Restore from Infinispan backup (if available)
3. Deploy previous version
4. Start nodes with Infinispan configuration
5. Verify data integrity

### 8.3 Rollback Testing

- Practice rollback in staging environment
- Document rollback time (target: <30 minutes)
- Verify data preservation during rollback
- Test partial rollback (some nodes on RAFT, some on Infinispan)

---

## 9. Success Criteria

### 9.1 Functional Requirements

- [ ] All existing LRA operations work correctly
- [ ] Leader election completes within 5 seconds
- [ ] Failover occurs within 10 seconds
- [ ] No data loss during node failures
- [ ] Snapshot and restore work correctly
- [ ] All existing tests pass with RAFT implementation

### 9.2 Performance Requirements

- [ ] Write latency < 100ms (p95) for 3-node cluster
- [ ] Read latency < 10ms (p95) for linearizable reads
- [ ] Throughput > 500 LRA operations/sec
- [ ] Leader election time < 5 seconds
- [ ] Failover time < 10 seconds
- [ ] Memory overhead < 20% increase vs Infinispan

### 9.3 Operational Requirements

- [ ] Clear monitoring dashboards
- [ ] Automated health checks
- [ ] Documented troubleshooting procedures
- [ ] Backup and restore procedures tested
- [ ] Rollback plan validated
- [ ] Migration guide complete

---

## 10. Timeline and Milestones

### Week 1: Preparation & Design
- **Day 1-2**: Architecture design, API design
- **Day 3-4**: Create new module, setup dependencies
- **Day 5**: Design review, update plan

### Week 2: Implementation
- **Day 1-2**: Implement RaftStore and LraStateMachine
- **Day 3**: Implement RaftClusterCoordinator
- **Day 4**: Implement snapshot mechanism
- **Day 5**: Code review, refactoring

### Week 3: Testing
- **Day 1-2**: Unit tests, fix bugs
- **Day 3**: Integration tests with WildFly
- **Day 4**: Failover and performance tests
- **Day 5**: Test review, bug fixes

### Week 4: Migration & Deployment
- **Day 1-2**: Create migration guide, update docs
- **Day 3**: Staging deployment, validation
- **Day 4**: Production deployment (gradual rollout)
- **Day 5**: Monitoring, issue resolution

---

## 11. Risks and Mitigation

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| Performance degradation | High | Medium | Benchmark early, optimize state machine |
| Data loss during migration | Critical | Low | Dual-write mode, extensive testing |
| RAFT library bugs | High | Low | Use stable version, thorough testing |
| Cluster instability | High | Medium | Proper configuration, monitoring |
| Complex rollback | Medium | Medium | Dual-mode support, practice rollback |
| Learning curve | Low | High | Training, documentation |

---

## 12. Next Steps

1. **Review and Approve Plan**
   - Stakeholder review
   - Technical review
   - Approve timeline and resources

2. **Setup Development Environment**
   - Create feature branch
   - Setup test cluster
   - Configure CI/CD

3. **Begin Implementation**
   - Create `lra-coordinator-ha-raft` module
   - Implement core classes
   - Write unit tests

4. **Continuous Validation**
   - Weekly progress reviews
   - Early performance testing
   - Adjust plan as needed

---

## 13. References

- **JGroups-Raft**: https://github.com/jgroups-extras/jgroups-raft
- **RAFT Paper**: https://raft.github.io/raft.pdf
- **JGroups Documentation**: http://www.jgroups.org/manual5/index.html
- **Infinispan Documentation**: https://infinispan.org/docs/stable/titles/overview/overview.html
- **LRA Specification**: https://github.com/eclipse/microprofile-lra

---

## Appendix A: Comparison Matrix

| Feature | Infinispan + JGroups | JGroups-Raft |
|---------|---------------------|--------------|
| **Consistency** | Eventual (sync replication) | Strong (RAFT consensus) |
| **Write Latency** | ~10-20ms | ~30-50ms |
| **Read Latency** | ~5ms (local) | ~5ms (dirty) / ~30ms (linearizable) |
| **Fault Tolerance** | N-1 failures | (N-1)/2 failures |
| **Split-Brain** | Configurable handling | Prevented by quorum |
| **Leader Election** | No leader concept | Built-in, automatic |
| **Ordering** | Per-key | Total order |
| **Complexity** | Medium | Medium-High |
| **Maturity** | Very mature | Mature |
| **Use Case** | High-performance caching | Critical data requiring consistency |

---

## Appendix B: Code Examples

### B.1 Creating RAFT Cluster

```java
// Node 1
JChannel channel1 = new JChannel("raft.xml");
RAFT raft1 = new RAFT().raftId("A").members("A,B,C");
channel1.getProtocolStack().insertProtocol(raft1, ProtocolStack.Position.ABOVE, NAKACK2.class);
ReplicatedStateMachine<String, LRAData> rsm1 = new ReplicatedStateMachine<>(channel1);
channel1.connect("lra-cluster");

// Node 2
JChannel channel2 = new JChannel("raft.xml");
RAFT raft2 = new RAFT().raftId("B").members("A,B,C");
channel2.getProtocolStack().insertProtocol(raft2, ProtocolStack.Position.ABOVE, NAKACK2.class);
ReplicatedStateMachine<String, LRAData> rsm2 = new ReplicatedStateMachine<>(channel2);
channel2.connect("lra-cluster");

// Node 3
JChannel channel3 = new JChannel("raft.xml");
RAFT raft3 = new RAFT().raftId("C").members("A,B,C");
channel3.getProtocolStack().insertProtocol(raft3, ProtocolStack.Position.ABOVE, NAKACK2.class);
ReplicatedStateMachine<String, LRAData> rsm3 = new ReplicatedStateMachine<>(channel3);
channel3.connect("lra-cluster");
```

### B.2 LRA Operation Serialization

```java
public class LraOperation implements Serializable {
    public enum Type { PUT, REMOVE, GET }
    
    private Type type;
    private String key;
    private LRAData value;
    
    public byt