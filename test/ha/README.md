# LRA High Availability Clustering Integration Tests

Comprehensive integration tests for LRA High Availability features with WildFly and Infinispan clustering.

## Overview

This test suite validates the HA coordinator implementation across multiple dimensions:

### Test Levels

1. **Component Tests** (`HAClusteringIT`) - Fast, automated tests for basic HA components
2. **Multi-Node Tests** (`MultiNodeClusterIT`) - Basic cluster replication tests
3. **Failure Scenario Tests** (`FailureScenarioIT`) - Node crashes, failover, partition handling
4. **Data Consistency Tests** (`DataConsistencyIT`) - State synchronization, locking, recovery
5. **Performance Tests** (`PerformanceIT`) - Throughput, latency, scalability

### What's Tested

- **InfinispanStore** - Distributed cache-based LRA state storage
- **InfinispanClusterCoordinator** - Cluster coordinator election and management
- **HA mode detection** - System property-based HA configuration
- **Node ID embedding** - LRA ID format differences between single-node and HA modes
- **Node identification** - Node ID configuration and fallback mechanisms
- **Network partition handling** - DENY_READ_WRITES strategy
- **State replication** - Synchronous replication across nodes
- **Recovery process** - ObjectStore to Infinispan cache rebuilding
- **Distributed locking** - Preventing concurrent modification conflicts
- **Performance characteristics** - Verifying <20% HA overhead target

## Prerequisites

1. **WildFly Application Server**
   ```bash
   export JBOSS_HOME=/path/to/wildfly
   ```

2. **WildFly must be configured with standalone-ha.xml** for clustering support

3. **JGroups** - Included in WildFly standalone-ha.xml configuration

## Running the Tests

### Quick Start - Component Tests (Default)

Run fast component-level tests (no WildFly required):
```bash
cd test/ha
mvn clean verify
```

This runs `HAClusteringIT` with ~1 second execution time.

### Multi-Node Cluster Tests

Requires `JBOSS_HOME` environment variable pointing to WildFly:

```bash
export JBOSS_HOME=/path/to/wildfly
cd test/ha
mvn clean verify -Parq -Pha-cluster-tests
```

### Failure Scenario Tests

Tests node crashes, coordinator failover, and partition handling:

```bash
export JBOSS_HOME=/path/to/wildfly
mvn clean verify -Parq -Pha-failure-tests
```

### Data Consistency Tests

Tests state synchronization, locking, and recovery:

```bash
export JBOSS_HOME=/path/to/wildfly
mvn clean verify -Parq -Pha-consistency-tests
```

### Performance Tests

Tests throughput, latency, and scalability:

```bash
export JBOSS_HOME=/path/to/wildfly
mvn clean verify -Parq -Pha-performance-tests
```

### Full Test Suite

Run all HA tests (comprehensive validation):

```bash
export JBOSS_HOME=/path/to/wildfly
mvn clean verify -Parq -Pha-full-suite
```

### Run a Specific Test Method

```bash
mvn clean verify -Parq -Pha-cluster-tests \
  -Dit.test=MultiNodeClusterIT#testCreateLRAOnNode1AndRetrieveFromNode2
```

### With Debug Logging

```bash
mvn clean verify -Parq -Pha-cluster-tests -Dwildfly.logging.level=DEBUG
```

## Test Profile Summary

| Profile | Test Suite | Execution Time | Prerequisites |
|---------|-----------|----------------|---------------|
| _(default)_ | Component tests | ~1 sec | None |
| `ha-cluster-tests` | Multi-node replication | ~2-5 min | WildFly |
| `ha-failure-tests` | Failure scenarios | ~5-10 min | WildFly |
| `ha-consistency-tests` | Data consistency | ~3-7 min | WildFly |
| `ha-performance-tests` | Performance benchmarks | ~5-15 min | WildFly |
| `ha-full-suite` | All tests | ~20-40 min | WildFly |

## Manual Multi-Node Cluster Testing

The `MultiNodeClusterIT` class provides a template for testing actual cluster behavior with two WildFly nodes. To use it for manual testing:

1. **Start two WildFly instances manually:**
   ```bash
   # Terminal 1 - Node 1
   cd $JBOSS_HOME
   ./bin/standalone.sh -c standalone-ha.xml \
     -Djboss.node.name=node1 \
     -Dlra.coordinator.ha.enabled=true \
     -Dlra.coordinator.node.id=node1

   # Terminal 2 - Node 2
   cd $JBOSS_HOME
   ./bin/standalone.sh -c standalone-ha.xml \
     -Djboss.node.name=node2 \
     -Djboss.socket.binding.port-offset=100 \
     -Dlra.coordinator.ha.enabled=true \
     -Dlra.coordinator.node.id=node2
   ```

2. **Deploy the LRA coordinator to both nodes**

3. **Adapt and run the test scenarios from MultiNodeClusterIT**:
   - Create LRA on one node, retrieve from another
   - Test concurrent LRAs across nodes
   - Test cross-node lifecycle operations
   - Verify node ID embedding in LRA IDs

## Test Architecture

### Cluster Setup

The tests use Arquillian to manage two WildFly instances:

- **Node 1**: `localhost:8080` (port offset 0)
  - Management port: 9990
  - Node name: `node1`

- **Node 2**: `localhost:8180` (port offset 100)
  - Management port: 10090
  - Node name: `node2`

### Cache Configuration

Infinispan caches use **REPL_SYNC** mode:
- `lra-active`: Active LRAs
- `lra-recovering`: LRAs in Closing/Cancelling state
- `lra-failed`: Failed LRAs (FailedToClose/FailedToCancel)

### Partition Handling

When a network partition occurs:
- **DENY_READ_WRITES**: Minority partition denies all operations
- **Merge policy**: PREFERRED_ALWAYS

## Test Scenarios

### Component-Level Tests (HAClusteringIT)

Fast tests verifying HA component functionality without requiring a full cluster:

- ✅ InfinispanStore initialization
- ✅ ClusterCoordinator initialization
- ✅ HA mode detection based on system properties
- ✅ Node ID embedding in LRA IDs (single-node vs HA mode)
- ✅ Node ID configuration and fallback mechanisms
- ✅ Store availability checks
- ✅ Provider fallback behavior
- ✅ Multiple node ID format compatibility

### Multi-Node Cluster Tests (MultiNodeClusterIT)

Tests deploying LRA coordinator to two WildFly nodes:

- ✅ Create LRA on node1, retrieve from node2
- ✅ Create LRA on node2, retrieve from node1
- ✅ Multiple concurrent LRAs across cluster
- ✅ Cross-node LRA lifecycle (create on node1, close from node2)
- ✅ Node ID embedding verification

### Failure Scenario Tests (FailureScenarioIT)

Critical HA failure mode testing:

- 🔥 **Node crash during LRA lifecycle** - Verify surviving node continues operations
- 🔥 **Recovery after both nodes restart** - Verify ObjectStore → Infinispan rebuilding
- 🔥 **Coordinator failover** - Verify new coordinator election and recovery
- 🔥 **Split-brain prevention** - Verify DENY_READ_WRITES strategy
- 🔥 **Graceful shutdown** - Verify state preservation
- 🔥 **Concurrent node failures** - Verify cluster recovery
- 🔥 **Node rejoin after crash** - Verify state synchronization

### Data Consistency Tests (DataConsistencyIT)

State integrity and consistency validation:

- 🔐 **State replication consistency** - Verify identical state across nodes
- 🔐 **Concurrent modification prevention** - Verify distributed locks work
- 🔐 **State transition replication** - Verify Active → Closing → Closed propagation
- 🔐 **Cache recovery from ObjectStore** - Verify recovery rebuilds cache correctly
- 🔐 **No lost updates under load** - Verify all operations complete successfully
- 🔐 **Consistent state after partial failure** - Verify no corruption
- 🔐 **Cache eviction and fallback** - Verify ObjectStore fallback works

### Performance Tests (PerformanceIT)

Performance characteristics and scalability:

- ⚡ **High throughput LRA creation** - Measure 100-1000 LRAs/sec
- ⚡ **Concurrent load across cluster** - 200+ concurrent operations
- ⚡ **Replication latency** - Target: ~2-5ms (architecture doc spec)
- ⚡ **Memory scaling** - Test 500-5000 LRAs
- ⚡ **LRA lifecycle performance** - Create → Close timing
- ⚡ **Cluster throughput capacity** - Sustained load testing
- ⚡ **Cross-node operation latency** - Same-node vs cross-node comparison

## Configuration

### System Properties

Tests use these system properties:
- `lra.coordinator.ha.enabled=true` - Enable HA mode
- `lra.coordinator.node.id` - Unique node identifier
- `jboss.node.name` - WildFly node name
- `jgroups.bind_addr=127.0.0.1` - JGroups bind address

### Arquillian Configuration

See `src/test/resources/arquillian.xml` for container configuration.

## Test Utilities

The `TestHelpers` class provides utilities for all test suites:

- **Node lifecycle**: `startNode()`, `stopNode()`, `killNode()`
- **Cluster waiting**: `waitForClusterFormation()`, `waitForReplication()`
- **LRA operations**: `startLRA()`, `closeLRA()`, `cancelLRA()`
- **Batch operations**: `startMultipleLRAs()`, `closeAllLRAs()`
- **Verification**: `isLRAAccessible()`, `isLRAReplicatedToNode()`
- **Metrics**: `Metrics` class for performance measurement

## Expected Test Results

### Component Tests
- **Execution time**: ~1 second
- **Success rate**: 100%
- **Requirements**: None (no WildFly needed)

### Multi-Node Tests
- **Execution time**: ~2-5 minutes
- **Success rate**: >95% (some timing-sensitive)
- **Requirements**: WildFly with standalone-ha.xml

### Failure Tests
- **Execution time**: ~5-10 minutes
- **Success rate**: >90% (chaos testing has variability)
- **Requirements**: WildFly, adequate system resources

### Consistency Tests
- **Execution time**: ~3-7 minutes
- **Success rate**: >95%
- **Requirements**: WildFly, stable network

### Performance Tests
- **Execution time**: ~5-15 minutes
- **Key metrics**:
  - Average LRA creation latency: <50ms
  - Replication latency: <100ms
  - Throughput: >10 LRAs/sec per node
  - HA overhead: <20% vs single-node (target from architecture doc)

## Troubleshooting

### Tests fail with "Could not start container"
- ✅ Check `JBOSS_HOME` is set: `echo $JBOSS_HOME`
- ✅ Verify WildFly has `standalone-ha.xml`: `ls $JBOSS_HOME/standalone/configuration/standalone-ha.xml`
- ✅ Ensure WildFly version is compatible (tested with WildFly 26+)

### Tests timeout waiting for cluster formation
- ✅ Increase timeout in test if needed (default: 10-15 seconds)
- ✅ Check firewall rules allow localhost communication
- ✅ Verify JGroups multicast is working: `grep -i jgroups $JBOSS_HOME/standalone/log/server.log`

### Replication not working
- ✅ Verify both nodes started successfully (check logs)
- ✅ Check WildFly logs for clustering errors: `grep -i "cluster\|infinispan" $JBOSS_HOME/standalone/log/server.log`
- ✅ Ensure standalone-ha.xml has Infinispan configured
- ✅ Verify system property `lra.coordinator.ha.enabled=true` is set

### Performance tests show high latency
- ✅ Verify system has adequate resources (CPU, memory)
- ✅ Check if garbage collection is impacting performance
- ✅ Ensure network latency is low (localhost should be <1ms)
- ✅ Consider increasing test iteration counts for better averaging

### Flaky test failures
- ✅ Increase wait times for replication (`waitForReplication()`)
- ✅ Verify no port conflicts (8080, 8180, 9990, 10090)
- ✅ Check for resource cleanup between tests
- ✅ Run tests with `-Parq` profile for proper container management

## Test Best Practices

1. **Always set JBOSS_HOME** before running cluster tests
2. **Run component tests first** to verify basic functionality
3. **Clean up between test runs** to avoid state pollution
4. **Monitor WildFly logs** for clustering issues
5. **Use appropriate test profiles** for targeted testing
6. **Allow adequate time** for cluster formation and replication
7. **Run performance tests multiple times** for statistical significance

## CI/CD Integration

### Recommended CI Setup

```yaml
# Fast feedback loop
- name: Component Tests
  run: mvn clean verify -Ptest/ha

# Nightly comprehensive validation
- name: Full HA Test Suite
  run: |
    export JBOSS_HOME=/path/to/wildfly
    mvn clean verify -Parq -Pha-full-suite
```

### Jenkins Pipeline Example

```groovy
stage('HA Component Tests') {
    steps {
        sh 'cd test/ha && mvn clean verify'
    }
}

stage('HA Cluster Tests') {
    when {
        branch 'main'
    }
    steps {
        sh '''
            export JBOSS_HOME=/opt/wildfly
            cd test/ha
            mvn clean verify -Parq -Pha-cluster-tests
        '''
    }
}
```

## Notes

- Tests run with `maven-failsafe-plugin` (not surefire) for integration test lifecycle
- Multi-node tests use `managed=false` deployments for explicit container control
- Each test manages its own container lifecycle (start/stop nodes)
- Tests are designed to be independent and can run in any order
- Cluster formation typically takes 5-15 seconds
- Some tests may show timing variability based on system load

## Related Documentation

- [PERSISTENCE-HA-ARCHITECTURE.md](../../coordinator/PERSISTENCE-HA-ARCHITECTURE.md) - Detailed HA architecture
- [Infinispan Documentation](https://infinispan.org/docs/stable/)
- [JGroups Documentation](http://www.jgroups.org/manual/)
- [Arquillian Documentation](https://arquillian.org/guides/)
- [WildFly Clustering Guide](https://docs.wildfly.org/latest/High_Availability_Guide.html)

## Contributing

When adding new HA tests:

1. Choose the appropriate test class based on test category
2. Use `TestHelpers` utilities for common operations
3. Follow the Given-When-Then test structure
4. Add cleanup in `@AfterEach` to prevent resource leaks
5. Document expected behavior and timing assumptions
6. Consider adding test to appropriate Maven profile
7. Update this README with new test descriptions
