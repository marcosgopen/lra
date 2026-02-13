# LRA High Availability Clustering Integration Tests

Integration tests for LRA High Availability features with WildFly and Infinispan clustering.

## Overview

These tests verify:
- **REPL_SYNC cache mode** - State replication across cluster nodes
- **ClusterCoordinator** - Coordinator election and failover
- **Network partition handling** - Infinispan partition handling in DENY_READ_WRITES mode
- **Distributed locking** - Lock coordination across nodes
- **Cache transitions** - Moving LRAs between active/recovering/failed caches

## Prerequisites

1. **WildFly Application Server**
   ```bash
   export JBOSS_HOME=/path/to/wildfly
   ```

2. **WildFly must be configured with standalone-ha.xml** for clustering support

3. **JGroups** - Included in WildFly standalone-ha.xml configuration

## Running the Tests

### Run all HA integration tests:
```bash
cd test/ha
mvn clean verify
```

### Run specific test:
```bash
mvn clean verify -Dit.test=HAClusteringIT#testReplSyncCacheReplication
```

### With debug logging:
```bash
mvn clean verify -Dwildfly.logging.level=DEBUG
```

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

### 1. testReplSyncCacheReplication
Verifies that LRA state saved on one node is replicated to other nodes.

### 2. testClusterCoordinatorElection
Checks that exactly one node is elected as cluster coordinator.

### 3. testCoordinatorFailover
Tests failover when the coordinator node fails:
1. Stop coordinator node
2. Surviving node takes over
3. Restart failed node
4. Cluster reforms

### 4. testCacheAvailability
Verifies cache availability mode (AVAILABLE vs DEGRADED).

### 5. testStateMoveBetweenCaches
Tests moving LRA state between active → recovering → failed caches with replication.

### 6. testConcurrentAccessWithReplication
Creates multiple LRAs concurrently and verifies replication.

## Configuration

### System Properties

Tests use these system properties:
- `lra.coordinator.ha.enabled=true` - Enable HA mode
- `lra.coordinator.node.id` - Unique node identifier
- `jboss.node.name` - WildFly node name
- `jgroups.bind_addr=127.0.0.1` - JGroups bind address

### Arquillian Configuration

See `src/test/resources/arquillian.xml` for container configuration.

## Troubleshooting

### Tests fail with "Could not start container"
- Check that `JBOSS_HOME` is set correctly
- Ensure WildFly has `standalone-ha.xml` configuration

### Tests timeout waiting for cluster formation
- Increase timeout in `waitForClusterFormation()` method
- Check firewall rules allow localhost communication
- Verify JGroups multicast is working

### Replication not working
- Verify both nodes started successfully
- Check WildFly logs for clustering errors
- Ensure standalone-ha.xml has Infinispan configured

## Notes

- Tests run with `maven-failsafe-plugin` (not surefire) because they require external containers
- Each test manages the container lifecycle (start/stop nodes)
- Tests are designed to be independent and can run in any order
- Cluster formation typically takes 5-10 seconds

## Related Documentation

- [Infinispan Documentation](https://infinispan.org/docs/stable/)
- [JGroups Documentation](http://www.jgroups.org/manual/)
- [Arquillian Documentation](https://arquillian.org/guides/)
- [WildFly Clustering Guide](https://docs.wildfly.org/latest/High_Availability_Guide.html)
