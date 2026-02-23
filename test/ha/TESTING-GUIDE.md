# LRA HA Testing Quick Reference Guide

## Quick Test Commands Cheat Sheet

### Fast Smoke Test (1 second)
```bash
cd test/ha
mvn clean verify
```

### Multi-Node Cluster Test (2-5 min)
```bash
export JBOSS_HOME=/path/to/wildfly
mvn clean verify -Parq -Pha-cluster-tests
```

### Failure Scenario Test (5-10 min)
```bash
export JBOSS_HOME=/path/to/wildfly
mvn clean verify -Parq -Pha-failure-tests
```

### Data Consistency Test (3-7 min)
```bash
export JBOSS_HOME=/path/to/wildfly
mvn clean verify -Parq -Pha-consistency-tests
```

### Performance Test (5-15 min)
```bash
export JBOSS_HOME=/path/to/wildfly
mvn clean verify -Parq -Pha-performance-tests
```

### Full Test Suite (20-40 min)
```bash
export JBOSS_HOME=/path/to/wildfly
mvn clean verify -Parq -Pha-full-suite
```

## Pre-Release Testing Checklist

Before deploying HA coordinator to production:

- [ ] **Component tests pass** - `mvn clean verify`
- [ ] **Multi-node replication works** - `mvn verify -Parq -Pha-cluster-tests`
- [ ] **Node crash recovery works** - `mvn verify -Parq -Pha-failure-tests`
- [ ] **Data consistency verified** - `mvn verify -Parq -Pha-consistency-tests`
- [ ] **Performance meets targets** - `mvn verify -Parq -Pha-performance-tests`
  - [ ] LRA creation latency < 50ms
  - [ ] Replication latency < 100ms
  - [ ] Throughput > 10 LRAs/sec
  - [ ] HA overhead < 20%
- [ ] **Full suite passes** - `mvn verify -Parq -Pha-full-suite`

## Test Environment Setup

### 1. Install WildFly
```bash
cd /opt
wget https://github.com/wildfly/wildfly/releases/download/26.1.3.Final/wildfly-26.1.3.Final.zip
unzip wildfly-26.1.3.Final.zip
export JBOSS_HOME=/opt/wildfly-26.1.3.Final
```

### 2. Verify WildFly HA Configuration
```bash
ls $JBOSS_HOME/standalone/configuration/standalone-ha.xml
```

### 3. Run Tests
```bash
cd /path/to/lra/test/ha
mvn clean verify -Parq -Pha-cluster-tests
```

## Interpreting Test Results

### Component Tests (HAClusteringIT)

**Success Output:**
```
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**What it means:**
- ✅ Basic HA components initialize correctly
- ✅ Node ID embedding works
- ✅ HA mode detection functional

### Multi-Node Tests (MultiNodeClusterIT)

**Success Output:**
```
Node ready: http://localhost:8080
Node ready: http://localhost:8180
Cluster formation complete
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
```

**What it means:**
- ✅ Cluster forms successfully
- ✅ State replicates between nodes
- ✅ Cross-node operations work

### Failure Tests (FailureScenarioIT)

**Success Output:**
```
Killing container: wildfly-ha-node1
LRA should still be accessible from node2 after node1 crash
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
```

**What it means:**
- ✅ Coordinator survives node crashes
- ✅ Failover works correctly
- ✅ Recovery rebuilds state

### Consistency Tests (DataConsistencyIT)

**Success Output:**
```
Created: 20, Closed: 20
Concurrent operations: 8 succeeded, 2 failed
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
```

**What it means:**
- ✅ No lost updates
- ✅ Distributed locks prevent conflicts
- ✅ State stays synchronized

### Performance Tests (PerformanceIT)

**Success Output:**
```
=== High Throughput Test ===
Metrics{operations=100, elapsed=3542ms, avgLatency=35.42ms, throughput=28.23 ops/s}
Average latency per LRA creation: 35.42 ms
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
```

**What it means:**
- ✅ Performance meets targets
- ✅ HA overhead acceptable (<20%)
- ✅ System scales well

## Common Test Failures and Solutions

### 1. "Could not start container"

**Symptom:**
```
org.jboss.arquillian.container.spi.client.container.LifecycleException:
Could not start container
```

**Solutions:**
- Verify `JBOSS_HOME`: `echo $JBOSS_HOME`
- Check WildFly exists: `ls $JBOSS_HOME/bin/standalone.sh`
- Verify standalone-ha.xml: `ls $JBOSS_HOME/standalone/configuration/standalone-ha.xml`

### 2. "Address already in use"

**Symptom:**
```
java.net.BindException: Address already in use
```

**Solutions:**
- Kill existing WildFly processes: `pkill -f wildfly`
- Check ports: `lsof -i :8080 -i :8180 -i :9990 -i :10090`
- Wait for cleanup between tests

### 3. "Cluster formation timeout"

**Symptom:**
```
RuntimeException: Node failed to become ready: http://localhost:8080
```

**Solutions:**
- Increase timeout in test configuration
- Check firewall: `sudo iptables -L`
- Verify multicast: `grep -i jgroups $JBOSS_HOME/standalone/log/server.log`
- Check system resources: `free -h`, `df -h`

### 4. "Replication not working"

**Symptom:**
```
AssertionError: LRA should be replicated to node2
```

**Solutions:**
- Verify both nodes started: Check both log files
- Increase replication wait time
- Check Infinispan configuration in standalone-ha.xml
- Verify HA system property: `-Dlra.coordinator.ha.enabled=true`

### 5. "Performance tests failing"

**Symptom:**
```
AssertionError: Average latency should be <50ms
```

**Solutions:**
- Run on less loaded system
- Increase test iteration count for better averaging
- Check for garbage collection pauses
- Verify network latency: `ping localhost`

## Debugging Tips

### 1. Enable Debug Logging
```bash
mvn clean verify -Parq -Pha-cluster-tests -Dwildfly.logging.level=DEBUG
```

### 2. Check WildFly Logs
```bash
# Node 1
tail -f $JBOSS_HOME/standalone/log/server.log

# If multiple runs, logs may be in different locations
find $JBOSS_HOME -name "server.log" -type f
```

### 3. Verify Cluster Status
```bash
# Look for cluster formation messages
grep -i "cluster\|view" $JBOSS_HOME/standalone/log/server.log

# Check Infinispan cache status
grep -i "infinispan\|cache" $JBOSS_HOME/standalone/log/server.log
```

### 4. Monitor Test Execution
```bash
# Run with -X for Maven debug output
mvn clean verify -Parq -Pha-cluster-tests -X
```

### 5. Keep Containers Running for Manual Testing
```bash
# Modify test to not cleanup in @AfterEach
# Then access coordinators at:
curl http://localhost:8080/lra-coordinator
curl http://localhost:8180/lra-coordinator
```

## Performance Baseline

Expected performance metrics (from architecture doc):

| Metric | Single-Node | HA Mode | Overhead |
|--------|-------------|---------|----------|
| LRA Creation | 7-15ms | 8-18ms | ~10-20% |
| LRA Join | 5-10ms | 7-15ms | ~10-20% |
| LRA Close | 5-10ms | 7-15ms | ~10-20% |
| Replication | N/A | 2-5ms | N/A |
| Distributed Lock | N/A | 1-3ms | N/A |

## Test Coverage Matrix

| Feature | Component | Multi-Node | Failure | Consistency | Performance |
|---------|-----------|------------|---------|-------------|-------------|
| Store initialization | ✅ | | | | |
| HA mode detection | ✅ | | | | |
| Node ID embedding | ✅ | ✅ | | | |
| State replication | | ✅ | | ✅ | |
| Cross-node ops | | ✅ | | | ✅ |
| Node crash | | | ✅ | | |
| Coordinator failover | | | ✅ | | |
| Split-brain prevention | | | ✅ | | |
| Recovery process | | | ✅ | ✅ | |
| Distributed locking | | | | ✅ | |
| No lost updates | | | | ✅ | |
| Throughput | | | | | ✅ |
| Latency | | | | | ✅ |
| Scalability | | | | | ✅ |

## Quick Validation Script

Save as `validate-ha.sh`:

```bash
#!/bin/bash
set -e

echo "=== LRA HA Test Validation ==="
echo

# Check prerequisites
echo "Checking prerequisites..."
if [ -z "$JBOSS_HOME" ]; then
    echo "❌ JBOSS_HOME not set"
    exit 1
fi
echo "✅ JBOSS_HOME: $JBOSS_HOME"

if [ ! -f "$JBOSS_HOME/standalone/configuration/standalone-ha.xml" ]; then
    echo "❌ standalone-ha.xml not found"
    exit 1
fi
echo "✅ standalone-ha.xml found"

# Run component tests
echo
echo "Running component tests..."
mvn clean verify -q
echo "✅ Component tests passed"

# Run cluster tests
echo
echo "Running cluster tests..."
mvn clean verify -Parq -Pha-cluster-tests -q
echo "✅ Cluster tests passed"

echo
echo "=== All validations passed! ==="
```

Run with:
```bash
chmod +x validate-ha.sh
./validate-ha.sh
```

## Next Steps After Testing

Once tests pass:

1. **Review test output** for any warnings or timing issues
2. **Check performance metrics** against baselines
3. **Verify logs** for any unexpected errors
4. **Document any findings** or anomalies
5. **Update configuration** based on test results
6. **Run load tests** in staging environment
7. **Monitor production** deployment closely

## Getting Help

If tests continue to fail:

1. Review logs in `$JBOSS_HOME/standalone/log/`
2. Check test output for specific error messages
3. Verify system meets minimum requirements
4. Consult [README.md](README.md) for detailed documentation
5. Check [PERSISTENCE-HA-ARCHITECTURE.md](../../coordinator/PERSISTENCE-HA-ARCHITECTURE.md) for architecture details
6. Open an issue with test output and logs
